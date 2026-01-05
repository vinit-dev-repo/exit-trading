$ErrorActionPreference = "Stop"

$base = Resolve-Path (Join-Path $PSScriptRoot "..")
$jar = if ($env:JAR_PATH) { $env:JAR_PATH } else { Join-Path $base "target\exit-trading-1.0.0.jar" }
$config = if ($env:CONFIG_PATH) { $env:CONFIG_PATH } else { Join-Path $base "src\main\resources\application.yml" }
$javaOpts = if ($env:JAVA_OPTS) { $env:JAVA_OPTS } else { "-Xms512m -Xmx1024m -Duser.timezone=Asia/Kolkata" }

$loaderPath = ""
if (Test-Path (Join-Path $base "lib\kiteconnect.jar")) {
  $loaderPath = "--loader.path=$base\lib\"
}

$args = @()
$args += ($javaOpts -split "\s+")
$args += "-jar"
$args += $jar
if ($loaderPath) { $args += $loaderPath }
$args += "--spring.config.location=file:$config"

& java @args