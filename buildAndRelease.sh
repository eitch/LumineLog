#!/bin/bash -e

# Initialize variables
DRY_RUN=true

# Parse arguments
while [[ "$#" -gt 0 ]]; do
  case $1 in
  --full) DRY_RUN=false ;;
  *)
    echo "Unknown parameter passed: $1"
    exit 1
    ;;
  esac
  shift
done

# Extract version from pom.xml
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)

if [[ "$DRY_RUN" == "false" ]]; then
  if [[ "$VERSION" == *SNAPSHOT* ]]; then
    echo "Current version is a SNAPSHOT: $VERSION"
    read -p "Do you really want to release a SNAPSHOT? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
      echo "Aborting release."
      exit 1
    fi
  fi
  echo "Performing FULL release..."
else
  echo "Performing DRY RUN release..."
fi

# Clean:
mvn clean
# Provision JDKS:
mvn -Pjdks
# Copy JARs:
mvn -Pjars
# Assemble Jlink distributions
mvn -Passemble

# Perform release
mvn jreleaser:full-release -Djreleaser.dry.run=$DRY_RUN