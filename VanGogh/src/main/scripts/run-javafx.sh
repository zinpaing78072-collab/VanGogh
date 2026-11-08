#!/bin/sh
# Wrapper for running JavaFX applications via exec-maven-plugin.
#
# The IDE task runner invokes:
#   mvn exec:exec -Dexec.executable=java -Dexec.args="-classpath %classpath vangogh.VanGoghApp"
#
# Because -Dexec.args fully replaces the pom <arguments> and JavaFX on JDK 17+
# must be launched with --module-path / --add-modules (it will not run on the
# plain classpath), this wrapper intercepts the runner's args, extracts the
# classpath, and re-invokes java with the required JavaFX module options.
#
# Expected argument shape:  -classpath <CP> <main-class> [app-args...]

set -eu

CP=""
MAIN=""
APP_ARGS=""

while [ $# -gt 0 ]; do
  case "$1" in
    -classpath)
      CP="$2"
      shift 2
      ;;
    -cp)
      CP="$2"
      shift 2
      ;;
    *)
      if [ -z "$MAIN" ]; then
        MAIN="$1"
      else
        APP_ARGS="$APP_ARGS $1"
      fi
      shift
      ;;
  esac
done

if [ -z "$CP" ] || [ -z "$MAIN" ]; then
  echo "Usage: run-javafx.sh -classpath <classpath> <main-class> [args...]" >&2
  exit 2
fi

# JavaFX modules required by the application.
MODULES="javafx.controls,javafx.fxml,javafx.web"

exec java --module-path "$CP" --add-modules "$MODULES" -classpath "$CP" $MAIN $APP_ARGS
