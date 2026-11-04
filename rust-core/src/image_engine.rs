use image::imageops::FilterType;
use image::{ImageFormat, Rgb, RgbImage};
use std::fs::File;
use std::path::Path;

pub fn resize_image_file(
    source_path: &str,
    target_path: &str,
    target_width: u32,
    target_height: u32,
    quality: u8,
    format_str: &str,
    orientation: u32,
) -> Result<u64, String> {
    let src = Path::new(source_path);
    if !src.exists() {
        return Err(format!("Source image does not exist: {}", source_path));
    }

    let mut img = image::ImageReader::open(src)
        .map_err(|e| format!("Failed to open image: {}", e))?
        .decode()
        .map_err(|e| format!("Failed to decode image: {}", e))?;

    // Apply EXIF orientation
    img = match orientation {
        2 => img.fliph(),
        3 => img.rotate180(),
        4 => img.flipv(),
        5 => img.rotate90().fliph(),
        6 => img.rotate90(),
        7 => img.rotate270().fliph(),
        8 => img.rotate270(),
        _ => img,
    };

    let target_w = target_width.max(1);
    let target_h = target_height.max(1);

    // High quality scaling with Triangle/CatmullRom filter for mobile performance
    let resized = img.resize_exact(target_w, target_h, FilterType::Triangle);

    let format_clean = format_str.trim().to_lowercase();
    let is_jpeg = matches!(format_clean.as_str(), "jpg" | "jpeg");
    let is_png = format_clean == "png";
    let is_webp = format_clean == "webp";

    if is_jpeg {
        // Flatten alpha onto white background for JPEG
        let rgb_img = if resized.color().has_alpha() {
            let rgba = resized.to_rgba8();
            let mut rgb = RgbImage::new(rgba.width(), rgba.height());
            for (x, y, pixel) in rgba.enumerate_pixels() {
                let a = pixel[3] as u32;
                let inv_a = 255 - a;
                let r = ((pixel[0] as u32 * a + 255 * inv_a) / 255) as u8;
                let g = ((pixel[1] as u32 * a + 255 * inv_a) / 255) as u8;
                let b = ((pixel[2] as u32 * a + 255 * inv_a) / 255) as u8;
                rgb.put_pixel(x, y, Rgb([r, g, b]));
            }
            rgb
        } else {
            resized.to_rgb8()
        };

        let mut out_file = File::create(target_path)
            .map_err(|e| format!("Failed to create output file: {}", e))?;
        let q = quality.clamp(1, 100);
        let mut encoder = image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out_file, q);
        encoder
            .encode_image(&rgb_img)
            .map_err(|e| format!("Failed to encode JPEG: {}", e))?;
    } else if is_png {
        resized
            .save_with_format(target_path, ImageFormat::Png)
            .map_err(|e| format!("Failed to save PNG: {}", e))?;
    } else if is_webp {
        resized
            .save_with_format(target_path, ImageFormat::WebP)
            .map_err(|e| format!("Failed to save WebP: {}", e))?;
    } else {
        // Fallback default
        resized
            .save(target_path)
            .map_err(|e| format!("Failed to save image: {}", e))?;
    }

    let meta = std::fs::metadata(target_path)
        .map_err(|e| format!("Failed to read metadata of resized file: {}", e))?;
    Ok(meta.len())
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::RgbaImage;

    #[test]
    fn test_resize_image_file() {
        let temp_dir = std::env::temp_dir().join("owlfm_image_test");
        let _ = std::fs::remove_dir_all(&temp_dir);
        std::fs::create_dir_all(&temp_dir).unwrap();

        let src_path = temp_dir.join("input.png");
        let dst_path = temp_dir.join("output.jpg");

        // Create 100x100 dummy RGBA image
        let img = RgbaImage::from_fn(100, 100, |x, y| {
            image::Rgba([(x % 256) as u8, (y % 256) as u8, 128, 255])
        });
        img.save_with_format(&src_path, ImageFormat::Png).unwrap();

        let bytes = resize_image_file(
            src_path.to_str().unwrap(),
            dst_path.to_str().unwrap(),
            50,
            50,
            85,
            "jpeg",
            1,
        ).unwrap();

        assert!(bytes > 0);
        assert!(dst_path.exists());

        // Verify decoded dimensions
        let decoded = image::ImageReader::open(&dst_path).unwrap().decode().unwrap();
        assert_eq!(decoded.width(), 50);
        assert_eq!(decoded.height(), 50);

        let _ = std::fs::remove_dir_all(&temp_dir);
    }
}

