#!/bin/bash
# Teste de estabilidade: milhares de toques, arrastes e teclas aleatórias no app (Monkey),
# depois procura travamentos (FATAL EXCEPTION) e congelamentos (ANR) no logcat.
set -x
adb install -r -g obd-elm327.apk
adb logcat -c
for seed in 1234 98765 4242; do
  adb shell monkey -p br.obd.app --pct-syskeys 0 --pct-appswitch 3 --pct-anyevent 0 \
    --throttle 80 -s $seed -v 2500 >> monkey.txt 2>&1 || true
done
# rotação e reabertura (configChanges)
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
adb shell monkey -p br.obd.app --pct-syskeys 0 --throttle 80 -s 777 -v 1000 >> monkey.txt 2>&1 || true
adb shell settings put system user_rotation 0
adb logcat -d > logcat.txt
set +x
events=$(grep -c ":Sending" monkey.txt)
crash=0
if grep -q "FATAL EXCEPTION" logcat.txt && grep -A3 "FATAL EXCEPTION" logcat.txt | grep -q "br.obd.app"; then crash=1; fi
if grep -q "CRASH: br.obd.app\|ANR in br.obd.app\|NOT RESPONDING: br.obd.app" monkey.txt logcat.txt; then crash=1; fi
if [ $crash -eq 1 ]; then
  grep -A30 "FATAL EXCEPTION" logcat.txt | head -80 | while IFS= read -r l; do echo "::error::$l"; done
  grep -A20 "CRASH: br.obd.app\|ANR in br.obd.app" monkey.txt logcat.txt | head -40 | while IFS= read -r l; do echo "::error::$l"; done
  exit 1
fi
echo "::notice::Estabilidade OK: $events eventos aleatórios sem travamento nem ANR"
