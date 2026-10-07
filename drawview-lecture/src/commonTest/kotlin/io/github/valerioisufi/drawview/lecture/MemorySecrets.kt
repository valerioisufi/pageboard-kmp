package io.github.valerioisufi.drawview.lecture

/** The secrets of the tests: in memory, never in the store of the system of the machine running them. */
internal class MemorySecrets : SecretStorage {
    private val values = mutableMapOf<String, String>()

    override fun read(name: String): String? = values[name]

    override fun write(name: String, value: String?): Boolean {
        if (value == null) values.remove(name) else values[name] = value
        return true
    }
}
