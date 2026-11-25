#!/usr/bin/env bash
# Build DupeGuard without Maven/Gradle.
#
# Downloads the Paper API (and the few compile-time dependencies javac actually
# needs), compiles the sources against Java 21, and packages a plugin jar.
#
# Usage: ./build.sh [output-dir]
set -euo pipefail

cd "$(dirname "$0")"

OUT_DIR="${1:-dist}"
LIB_DIR="build/lib"
CLASSES_DIR="build/classes"
# Override to compile against another Minecraft version, e.g.
#   PAPER_API_VERSION=1.21.4-R0.1-SNAPSHOT ./build.sh
PAPER_API_VERSION="${PAPER_API_VERSION:-1.21.11-R0.1-SNAPSHOT}"
PAPER_REPO="https://repo.papermc.io/repository/maven-public"
MAVEN_CENTRAL="https://repo1.maven.org/maven2"

# Resolve the current snapshot build of Paper API.
SNAPSHOT_VALUE="$(
  curl -fsSL "${PAPER_REPO}/io/papermc/paper/paper-api/${PAPER_API_VERSION}/maven-metadata.xml" \
    | grep -o '<value>[^<]*</value>' | head -1 | sed 's/<[^>]*>//g'
)"
if [ -z "${SNAPSHOT_VALUE}" ]; then
  echo "error: could not resolve paper-api snapshot for ${PAPER_API_VERSION}" >&2
  exit 1
fi
echo "paper-api snapshot: ${SNAPSHOT_VALUE}"

mkdir -p "${LIB_DIR}" "${CLASSES_DIR}" "${OUT_DIR}"

fetch() {
  local url="$1" dest="$2"
  if [ ! -s "${dest}" ]; then
    echo "fetching $(basename "${dest}")"
    curl -fsSL "${url}" -o "${dest}"
  fi
}

fetch "${PAPER_REPO}/io/papermc/paper/paper-api/${PAPER_API_VERSION}/paper-api-${SNAPSHOT_VALUE}.jar" \
      "${LIB_DIR}/paper-api.jar"
fetch "${MAVEN_CENTRAL}/net/kyori/adventure-api/4.26.1/adventure-api-4.26.1.jar" \
      "${LIB_DIR}/adventure-api.jar"
fetch "${MAVEN_CENTRAL}/net/kyori/adventure-key/4.26.1/adventure-key-4.26.1.jar" \
      "${LIB_DIR}/adventure-key.jar"
fetch "${MAVEN_CENTRAL}/net/kyori/examination-api/1.3.0/examination-api-1.3.0.jar" \
      "${LIB_DIR}/examination-api.jar"
fetch "${MAVEN_CENTRAL}/com/google/guava/guava/33.3.1-jre/guava-33.3.1-jre.jar" \
      "${LIB_DIR}/guava.jar"
fetch "${MAVEN_CENTRAL}/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar" \
      "${LIB_DIR}/jspecify.jar"

CP="$(find "${LIB_DIR}" -name '*.jar' | tr '\n' ':')"

rm -rf "${CLASSES_DIR}"
mkdir -p "${CLASSES_DIR}"
find src/main/java -name '*.java' > build/sources.txt

javac --release 21 -encoding UTF-8 -Xlint:all \
      -cp "${CP}" -d "${CLASSES_DIR}" @build/sources.txt

cp -r src/main/resources/. "${CLASSES_DIR}/"

VERSION="$(grep '^version:' src/main/resources/plugin.yml | awk '{print $2}')"
JAR="${OUT_DIR}/DupeGuard-${VERSION}.jar"
rm -f "${JAR}"
(cd "${CLASSES_DIR}" && jar cf "$(cd "${OLDPWD}/${OUT_DIR}" && pwd)/DupeGuard-${VERSION}.jar" .)

echo "built ${JAR}"
