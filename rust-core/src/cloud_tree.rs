use std::collections::HashMap;
use std::fs::File;
use std::io::{BufReader, BufWriter};
use std::sync::RwLock;
use rayon::prelude::*;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct CloudNode {
    pub id: String,
    pub parent_id: String,
    pub name: String,
    pub is_dir: bool,
    pub size: i64,
    pub last_modified: i64,
    pub extra: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct CloudTreeIndex {
    pub nodes: Vec<CloudNode>,
    pub id_to_index: HashMap<String, usize>,
    pub parent_to_children: HashMap<String, Vec<usize>>,
    pub root_id: String,
    pub timestamp: i64,
}

impl CloudTreeIndex {
    pub fn new(nodes: Vec<CloudNode>, root_id: String, timestamp: i64) -> Self {
        let mut id_to_index = HashMap::with_capacity(nodes.len());
        let mut parent_to_children: HashMap<String, Vec<usize>> = HashMap::new();

        for (idx, node) in nodes.iter().enumerate() {
            id_to_index.insert(node.id.clone(), idx);
            if !node.parent_id.is_empty() {
                parent_to_children
                    .entry(node.parent_id.clone())
                    .or_default()
                    .push(idx);
            }
        }

        Self {
            nodes,
            id_to_index,
            parent_to_children,
            root_id,
            timestamp,
        }
    }

    pub fn get_children(&self, parent_id: &str, sort_option: i32) -> Vec<CloudNode> {
        let child_indices = match self.parent_to_children.get(parent_id) {
            Some(indices) => indices,
            None => return Vec::new(),
        };

        let mut result: Vec<CloudNode> = child_indices
            .iter()
            .filter_map(|&idx| self.nodes.get(idx).cloned())
            .collect();

        // Sort items: directories first, then by selected option
        result.sort_by(|a, b| {
            if a.is_dir != b.is_dir {
                return b.is_dir.cmp(&a.is_dir); // true (dir) before false (file)
            }
            match sort_option {
                1 => b.name.to_lowercase().cmp(&a.name.to_lowercase()), // Name DESC
                2 => a.last_modified.cmp(&b.last_modified),             // Date ASC
                3 => b.last_modified.cmp(&a.last_modified),             // Date DESC
                4 => a.size.cmp(&b.size),                               // Size ASC
                5 => b.size.cmp(&a.size),                               // Size DESC
                _ => a.name.to_lowercase().cmp(&b.name.to_lowercase()), // Name ASC (default)
            }
        });

        result
    }

    pub fn get_child_counts(&self, parent_id: &str) -> (i32, i32, i32) {
        if let Some(indices) = self.parent_to_children.get(parent_id) {
            let mut subfolders = 0i32;
            let mut files = 0i32;
            for &idx in indices {
                if let Some(node) = self.nodes.get(idx) {
                    if node.is_dir {
                        subfolders += 1;
                    } else {
                        files += 1;
                    }
                }
            }
            (subfolders + files, subfolders, files)
        } else {
            (0, 0, 0)
        }
    }

    pub fn search(&self, query: &str, max_results: usize) -> Vec<CloudNode> {
        let q = query.to_lowercase();
        self.nodes
            .par_iter()
            .filter(|n| n.name.to_lowercase().contains(&q))
            .take_any(max_results)
            .cloned()
            .collect()
    }
}

static TREES: RwLock<Option<HashMap<String, CloudTreeIndex>>> = RwLock::new(None);

fn with_trees_read<F, R>(f: F) -> R
where
    F: FnOnce(&HashMap<String, CloudTreeIndex>) -> R,
{
    let guard = TREES.read().unwrap();
    if let Some(ref map) = *guard {
        f(map)
    } else {
        let empty = HashMap::new();
        f(&empty)
    }
}

fn with_trees_write<F, R>(f: F) -> R
where
    F: FnOnce(&mut HashMap<String, CloudTreeIndex>) -> R,
{
    let mut guard = TREES.write().unwrap();
    if guard.is_none() {
        *guard = Some(HashMap::new());
    }
    f(guard.as_mut().unwrap())
}

pub fn store_tree(account_id: &str, nodes: Vec<CloudNode>, root_id: &str) {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0);
    let index = CloudTreeIndex::new(nodes, root_id.to_string(), now);
    with_trees_write(|map| {
        map.insert(account_id.to_string(), index);
    });
}

pub fn has_tree(account_id: &str) -> bool {
    with_trees_read(|map| map.contains_key(account_id))
}

pub fn get_root_id(account_id: &str) -> Option<String> {
    with_trees_read(|map| map.get(account_id).map(|t| t.root_id.clone()))
}

pub fn get_children(account_id: &str, parent_id: &str, sort_option: i32) -> Vec<CloudNode> {
    with_trees_read(|map| match map.get(account_id) {
        Some(tree) => tree.get_children(parent_id, sort_option),
        None => Vec::new(),
    })
}

pub fn get_child_counts(account_id: &str, parent_id: &str) -> (i32, i32, i32) {
    with_trees_read(|map| match map.get(account_id) {
        Some(tree) => tree.get_child_counts(parent_id),
        None => (0, 0, 0),
    })
}

pub fn search_tree(account_id: &str, query: &str, max_results: usize) -> Vec<CloudNode> {
    with_trees_read(|map| match map.get(account_id) {
        Some(tree) => tree.search(query, max_results),
        None => Vec::new(),
    })
}

pub fn save_tree_to_file(account_id: &str, file_path: &str) -> Result<(), String> {
    let tree = with_trees_read(|map| map.get(account_id).cloned());
    let tree = tree.ok_or_else(|| format!("Tree for account {} not found in memory", account_id))?;

    let file = File::create(file_path).map_err(|e| format!("Failed to create tree file: {}", e))?;
    let writer = BufWriter::new(file);
    bincode::serialize_into(writer, &tree)
        .map_err(|e| format!("Failed to serialize tree: {}", e))
}

pub fn load_tree_from_file(account_id: &str, file_path: &str) -> Result<usize, String> {
    let file = File::open(file_path).map_err(|e| format!("Failed to open tree file: {}", e))?;
    let reader = BufReader::new(file);
    let tree: CloudTreeIndex = bincode::deserialize_from(reader)
        .map_err(|e| format!("Failed to deserialize tree: {}", e))?;
    let count = tree.nodes.len();
    with_trees_write(|map| {
        map.insert(account_id.to_string(), tree);
    });
    Ok(count)
}

pub fn invalidate_tree(account_id: &str) {
    with_trees_write(|map| {
        map.remove(account_id);
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_cloud_tree_operations() {
        let nodes = vec![
            CloudNode {
                id: "root".into(),
                parent_id: "".into(),
                name: "Root".into(),
                is_dir: true,
                size: 0,
                last_modified: 100,
                extra: "".into(),
            },
            CloudNode {
                id: "dir1".into(),
                parent_id: "root".into(),
                name: "Documents".into(),
                is_dir: true,
                size: 0,
                last_modified: 200,
                extra: "".into(),
            },
            CloudNode {
                id: "file1".into(),
                parent_id: "root".into(),
                name: "alpha.txt".into(),
                is_dir: false,
                size: 1024,
                last_modified: 300,
                extra: "".into(),
            },
            CloudNode {
                id: "file2".into(),
                parent_id: "dir1".into(),
                name: "beta.doc".into(),
                is_dir: false,
                size: 2048,
                last_modified: 400,
                extra: "".into(),
            },
        ];

        store_tree("acc_1", nodes, "root");
        assert!(has_tree("acc_1"));
        assert_eq!(get_root_id("acc_1"), Some("root".into()));

        // Children of root: dir1 (Documents) and file1 (alpha.txt)
        let children = get_children("acc_1", "root", 0);
        assert_eq!(children.len(), 2);
        assert!(children[0].is_dir); // Documents first
        assert_eq!(children[0].name, "Documents");
        assert_eq!(children[1].name, "alpha.txt");

        // Counts of root
        let counts = get_child_counts("acc_1", "root");
        assert_eq!(counts, (2, 1, 1)); // 2 total, 1 folder, 1 file

        // Counts of dir1
        let counts_dir1 = get_child_counts("acc_1", "dir1");
        assert_eq!(counts_dir1, (1, 0, 1)); // 1 total, 0 folder, 1 file

        // Search
        let search_res = search_tree("acc_1", "beta", 10);
        assert_eq!(search_res.len(), 1);
        assert_eq!(search_res[0].name, "beta.doc");

        // Invalidate
        invalidate_tree("acc_1");
        assert!(!has_tree("acc_1"));
    }
}
