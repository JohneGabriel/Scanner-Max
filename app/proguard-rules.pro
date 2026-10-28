# createRfcommSocket é chamado por reflexão numa classe do Android (não do app),
# então o R8 não precisa de regra para ele. Mantemos só os nomes nas pilhas de erro.
-keepattributes SourceFile,LineNumberTable

# usb-serial-for-android descobre os drivers por reflexão (getSupportedDevices)
-keep class com.hoho.android.usbserial.driver.** { *; }
