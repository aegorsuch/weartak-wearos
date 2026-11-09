#!/bin/sh
set -eu

echo "AGP diagnostic source: ${CI_COMMIT_SHA:-unknown}"
java -version
sh ./gradlew --version

status=0
for url in \
  'https://dl.google.com/dl/android/maven2/com/android/application/com.android.application.gradle.plugin/8.13.2/com.android.application.gradle.plugin-8.13.2.pom' \
  'https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.13.2/gradle-8.13.2.pom'
do
  printf 'Checking %s\n' "$url"
  if curl --fail --silent --show-error --location \
    --connect-timeout 10 --max-time 30 --output /dev/null \
    --write-out 'HTTP %{http_code}\n' "$url"
  then
    echo "POM reachable."
  else
    result=$?
    printf 'POM request failed (curl exit %s).\n' "$result"
    status=1
  fi
done

# Run even after a curl failure: Java and curl can use different trust/proxy settings.
if sh ./gradlew --no-daemon help --info --stacktrace --console=plain
then
  echo "Gradle configuration succeeded; no APK built or signing task executed."
else
  result=$?
  printf 'Gradle configuration failed (exit %s).\n' "$result"
  status=1
fi

exit "$status"
