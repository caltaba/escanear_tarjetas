# Escáner Tarjetas (Android)

Lee números de tarjetas con la cámara (OCR de Google ML Kit, funciona sin internet),
los va acumulando en una lista y los exporta a TXT o CSV.

## Obtener la APK sin instalar nada (GitHub)
1. Crea un repositorio nuevo en github.com (puede ser privado).
2. Sube TODO el contenido de esta carpeta (incluida la carpeta oculta `.github`).
3. Pestaña **Actions** -> "Compilar APK" se lanza sola (o pulsa *Run workflow*).
4. Al terminar (~5 min), entra en la ejecución y descarga **EscanerTarjetas-apk** (zip con la APK).
5. Pasa la APK al móvil, ábrela y permite "instalar apps de origen desconocido".

## Con Android Studio
Abrir la carpeta -> esperar a que sincronice -> Build > Build APK(s).
La APK queda en `app/build/outputs/apk/debug/app-debug.apk`.
