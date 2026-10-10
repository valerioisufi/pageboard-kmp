package io.github.valerioisufi.drawview.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** The secrets in the Keychain (generic passwords of the service of DrawView, on this device only). */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual object SecureStore {
    private const val SERVICE = "io.github.valerioisufi.drawview"

    actual fun read(name: String): String? = memScoped {
        val query = query(name, withData = null, returnData = true)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        NSString.create(data, NSUTF8StringEncoding)?.toString()
    }

    actual fun write(name: String, value: String?): Boolean {
        val deleteQuery = query(name, withData = null, returnData = false)
        SecItemDelete(deleteQuery)
        CFRelease(deleteQuery)
        if (value == null) return true
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return false
        val addQuery = query(name, withData = data, returnData = false)
        val status = SecItemAdd(addQuery, null)
        CFRelease(addQuery)
        return status == errSecSuccess
    }

    /** The query of the secret [name] (with [withData] to add it, [returnData] to read it). */
    private fun query(name: String, withData: NSData?, returnData: Boolean): CFDictionaryRef? {
        val dictionary = CFDictionaryCreateMutable(null, 6, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val retained = mutableListOf<Any?>()
        fun bridged(value: Any) = CFBridgingRetain(value).also { retained.add(it) }
        CFDictionaryAddValue(dictionary, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dictionary, kSecAttrService, bridged(SERVICE))
        CFDictionaryAddValue(dictionary, kSecAttrAccount, bridged(name))
        if (withData != null) {
            CFDictionaryAddValue(dictionary, kSecValueData, bridged(withData))
            CFDictionaryAddValue(dictionary, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        }
        if (returnData) {
            CFDictionaryAddValue(dictionary, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(dictionary, kSecMatchLimit, kSecMatchLimitOne)
        }
        // The dictionary keeps its values
        retained.forEach { CFRelease(it as platform.CoreFoundation.CFTypeRef?) }
        return dictionary
    }
}
