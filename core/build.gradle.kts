plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Deliberately a plain JVM module with no Android dependency: the chart format,
// the timing math and the judgment rules are the parts most worth testing, and
// keeping them off-device means the tests run in milliseconds.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
