# Camry Launcher

Pantalla de inicio para el estéreo Android TS18 (1024×600) del Camry 2008.

- Panel central con la velocidad del GPS y el Camry blanco con techo azul avanzando sobre el camino.
- Botón grande para abrir **CarPlay (TLINK5)**, y apertura automática al conectar el iPhone por USB.
- Tarjeta de música con lo que suena (Bluetooth, Spotify, etc.).
- Barra con Mapas, CarPlay, Música, Teléfono, Radio, Cámara, Ajustes y Apps.
  **Mantén presionado** cualquier botón para elegir qué app abre.
- **Temas y opciones**: botón de la paleta (o mantén presionado el panel central).
  Incluye *Mi Camry* (oscuro y claro) con la foto del auto y el velocímetro, sin animación.
- **Saludo de voz** al encender: "Bienvenidos Diana y Jahaz, ¿a dónde vamos hoy?" (el texto se puede cambiar en Opciones).

## Descargar e instalar

1. En este repositorio entra a **Releases** y descarga el `CamryLauncher.apk` más reciente.
2. Pásalo al estéreo con una USB y ábrelo con el administrador de archivos para instalarlo.
3. Abre **Camry Launcher**, acepta los permisos (ubicación y almacenamiento).
4. Ajustes → Aplicaciones → Apps predeterminadas → App de inicio → **Camry Launcher**.
5. Para ver la música: toca la tarjeta de música y activa **"Camry Launcher (música)"**.

## Crear tus propios temas

Al abrirse por primera vez, el launcher crea la carpeta `CamryLauncher/temas` en el
almacenamiento interno, con un tema de ejemplo (`MiTema`) y un instructivo (`LEEME.txt`).

Cada tema es una carpeta con un `tema.json`:

```json
{
  "nombre": "Mi tema",
  "fondo": "#06080B",
  "tarjeta": "#CC10141A",
  "boton": "#181D25",
  "camino": "#0A0D12",
  "acento": "#3D8BFF",
  "texto": "#E8EDF3",
  "textoSecundario": "#8D97A5",
  "carroceria": "#F4F6F8",
  "techo": "#1F4FA8",
  "imagenFondo": "fondo.png"
}
```

`imagenFondo` es opcional (un PNG/JPG de 1024×600 dentro de la misma carpeta).
Usa colores con transparencia en `tarjeta` (`#CC...`) para que se vea la imagen.

## Compilar

Cada cambio en `main` se compila solo con GitHub Actions y publica el APK en **Releases**.
