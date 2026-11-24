#!/bin/sh
# Runs the checks of the runtime selection, the JVM argument filter and the file validator on a
# normal JDK, without Android. The classes under test are the real ones from app_mojo; Android
# classes they touch (Log, annotations, R) are replaced by the small stubs in this folder.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../../app_mojo/src/main/java/git/artdeell/mojo"
OUT=$(mktemp -d)
java -m jdk.compiler/com.sun.tools.javac.Main -d "$OUT" $(find "$HERE" -name "*.java") \
  "$SRC/multirt/Runtime.java" "$SRC/multirt/RuntimeSelector.java" \
  "$SRC/utils/jre/JvmArgSanitizer.java" "$SRC/utils/ContentValidator.java" \
  "$SRC/modding/modpacks/models/ContentType.java"
java -cp "$OUT" git.artdeell.mojo.multirt.SelectorTest
java -cp "$OUT" git.artdeell.mojo.utils.jre.SanTest
java -cp "$OUT" git.artdeell.mojo.utils.CvTest
