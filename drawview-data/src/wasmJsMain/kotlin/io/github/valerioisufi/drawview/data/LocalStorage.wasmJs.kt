@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.valerioisufi.drawview.data

internal actual fun localStorageAvailable(): Boolean = jsLocalStorageAvailable()

internal actual fun localStorageGet(key: String): String? = jsLocalStorageGet(key)

internal actual fun localStorageSet(key: String, value: String): Boolean = jsLocalStorageSet(key, value)

internal actual fun localStorageRemove(key: String) = jsLocalStorageRemove(key)

private fun jsLocalStorageAvailable(): Boolean =
    js("{ try { return typeof window !== 'undefined' && window.localStorage != null; } catch (e) { return false; } }")

private fun jsLocalStorageGet(key: String): String? =
    js("{ try { return window.localStorage.getItem(key); } catch (e) { return null; } }")

private fun jsLocalStorageSet(key: String, value: String): Boolean =
    js("{ try { window.localStorage.setItem(key, value); return true; } catch (e) { return false; } }")

private fun jsLocalStorageRemove(key: String): Unit =
    js("{ try { window.localStorage.removeItem(key); } catch (e) { } }")
