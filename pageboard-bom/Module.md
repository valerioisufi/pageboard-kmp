# Module pageboard-bom

The versions of the Pageboard libraries taken together. An application imports it and declares the
libraries it uses without a version:

```kotlin
dependencies {
    implementation(platform("io.github.valerioisufi:pageboard-bom:0.1.0"))
    implementation("io.github.valerioisufi:pageboard-explorer")
    implementation("io.github.valerioisufi:pageboard-store-room")
}
```

Every library depends on it as well: when an application asks for two libraries at two
versions, Gradle takes all of them at the highest one, since a 0.x version may break the API
between the libraries.
