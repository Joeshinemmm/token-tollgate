import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
	kotlin("jvm") version "2.3.21" apply false
	kotlin("plugin.spring") version "2.3.21" apply false
	id("org.springframework.boot") version "4.1.2" apply false
	id("io.spring.dependency-management") version "1.1.7" apply false
}

subprojects {
	group = "tokentollgate"
	version = "0.1.0-SNAPSHOT"

	repositories {
		mavenCentral()
	}

	plugins.withId("org.jetbrains.kotlin.jvm") {
		extensions.configure<KotlinJvmProjectExtension> {
			jvmToolchain(25)
			compilerOptions {
				freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
			}
		}
	}

	tasks.withType<Test> {
		useJUnitPlatform()
	}
}
