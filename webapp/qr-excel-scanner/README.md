# Escaner QR a Excel

App web de una sola pagina que usa la camara del movil para leer codigos QR
y guarda cada lectura (contenido + fecha/hora) en una tabla. Con un boton se
descarga todo lo escaneado como un archivo Excel (`.xlsx`).

No necesita instalacion, servidor backend ni build: es HTML/CSS/JS con las
librerias `jsQR` (decodificar QR) y `SheetJS/xlsx` (generar el Excel) ya
incluidas en `vendor/`, para que funcione sin conexion a internet.

## Como usarla

Los navegadores solo dan acceso a la camara (`getUserMedia`) en un origen
seguro: `https://` o `http://localhost`. Abrir `index.html` directamente con
`file://` no funciona en la mayoria de moviles.

Opciones rapidas:

1. **Servidor local** (recomendado para probar en el propio ordenador o en
   el movil dentro de la misma red):
   ```bash
   cd webapp/qr-excel-scanner
   python3 -m http.server 8000
   ```
   Abre `http://localhost:8000` en el ordenador, o `http://<ip-del-pc>:8000`
   desde el movil (en ese caso el movil pedira "sitio no seguro" para la
   camara salvo que uses HTTPS; en Chrome Android puedes forzarlo marcando
   la IP como origen seguro en `chrome://flags/#unsafely-treat-insecure-origin-as-secure`).

2. **GitHub Pages / cualquier hosting estatico con HTTPS**: sube la carpeta
   `webapp/qr-excel-scanner` tal cual; al ser HTTPS, la camara funciona
   directamente en cualquier movil sin configuracion adicional.

## Flujo de la app

1. Pulsar **Activar camara** y dar permiso de camara al navegador.
2. Encuadrar el QR dentro del marco; al detectarlo se guarda automaticamente
   una fila con el contenido y la fecha/hora (con una pequena vibracion de
   confirmacion si el dispositivo lo soporta). El mismo codigo no se vuelve
   a guardar si se lee otra vez en menos de 3 segundos, para evitar
   duplicados accidentales.
3. Las lecturas se guardan en el navegador (`localStorage`), asi que siguen
   ahi si recargas la pagina.
4. Pulsar **Descargar Excel (.xlsx)** para generar y descargar el archivo
   con todas las lecturas. Cada descarga incluye la fecha/hora en el nombre
   del fichero.
5. **Vaciar todo** borra las lecturas guardadas (pide confirmacion).

## Estructura

```
webapp/qr-excel-scanner/
  index.html            Pagina principal (UI + logica)
  vendor/jsQR.js         Decodificador de QR (MIT)
  vendor/xlsx.core.min.js Generador de archivos Excel (SheetJS, Apache-2.0)
  README.md
```
