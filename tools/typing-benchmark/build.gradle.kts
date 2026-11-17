plugins { id("com.android.application") }
android {
    namespace="org.lokey.benchmark"
    compileSdk=37
    defaultConfig { applicationId="org.lokey.benchmark";minSdk=28;targetSdk=37;versionCode=1;versionName="1" }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
}
