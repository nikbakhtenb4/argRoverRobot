plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
dependencies {
    api(libs.gson)
    testImplementation(libs.junit)
}
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
