package com.antigravity.filemanager.data.remote.ftp

import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.usermanager.AnonymousAuthentication
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission

/** Single shared password (or none, for anonymous access) instead of a real multi-user account
 * system — every login, whatever the username, maps to the same home directory and permissions. */
class CustomFtpUserManager(
    private val homeDirectory: String,
    private val expectedPassword: String
) : UserManager {

    private val authorities: List<Authority> = listOf(
        WritePermission(),
        ConcurrentLoginPermission(100, 100),
        TransferRatePermission(0, 0)
    )

    private fun createUser(name: String): BaseUser {
        val user = BaseUser()
        user.name = name
        user.password = expectedPassword
        user.homeDirectory = homeDirectory
        user.authorities = authorities
        user.maxIdleTime = 600
        user.setEnabled(true)
        return user
    }

    override fun getUserByName(username: String?): User? {
        val name = if (username.isNullOrBlank()) "anonymous" else username
        return createUser(name)
    }

    override fun getAllUserNames(): Array<String> = arrayOf("admin", "anonymous")

    override fun delete(username: String?) {}

    override fun save(user: User?) {}

    override fun doesExist(username: String?): Boolean = true

    override fun authenticate(authentication: Authentication?): User {
        if (authentication == null) {
            throw AuthenticationFailedException("Authentication required")
        }

        // Case 1: Anonymous Authentication
        if (authentication is AnonymousAuthentication) {
            if (expectedPassword.isEmpty()) {
                return createUser("anonymous")
            } else {
                throw AuthenticationFailedException("Password is required for this server")
            }
        }

        // Case 2: Username & Password Authentication
        if (authentication is UsernamePasswordAuthentication) {
            val username = authentication.username?.trim() ?: "admin"
            val password = authentication.password ?: ""

            if (expectedPassword.isEmpty()) {
                // No password set on server: accept ANY username and password!
                return createUser(username)
            } else if (password == expectedPassword) {
                return createUser(username)
            } else {
                throw AuthenticationFailedException("Invalid password for user $username")
            }
        }

        throw AuthenticationFailedException("Unsupported authentication method")
    }

    override fun getAdminName(): String = "admin"

    override fun isAdmin(username: String?): Boolean = true
}
