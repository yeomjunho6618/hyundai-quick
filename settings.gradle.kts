// 프로젝트 전체 설정: 어디서 라이브러리를 받을지, 어떤 모듈이 있는지
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 차량 SDK(Vehicle, NaviHelper)는 Pleos 저장소에서 받는다
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}
// 폴더 이름은 옛 프로젝트명 그대로 둠
rootProject.name = "reverse-map-probe"
// 모듈은 app 하나
include(":app")
