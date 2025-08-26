# 📱 Android Text Editor and Kotlin Compiler

A powerful **text editor app** with Kotlin syntax highlighting and a compiler bridge via ADB. Write, edit, and compile Kotlin code directly from your Android device with a sleek, modern interface built using Jetpack Compose.


---

## ✨ Features

- 📝 **Edit & Save**: Create, open, and save text or code files.
- 🔄 **Undo/Redo**: Effortlessly undo or redo changes.
- 📊 **Word/Character Count**: Track your progress in real-time.
- 🔍 **Find & Replace**: Search with case-sensitive and whole-word options.
- 🎨 **Syntax Highlighting**: Supports Kotlin syntax highlighting natively. Custom JSON config can load for syntax highlighting of a specific language.
- ⚙️ **Compile Kotlin**: Seamlessly compile Kotlin code using a Flask server and ADB bridge and view output in the app.
- 🚨 **Error Highlighting**: View compiler errors with line numbers directly in the editor.
- 📱 **Mobile-First**: Optimized for Android 9+ with a clean, intuitive UI.

---

## 📦 Requirements

### 🖥️ Server-Side (PC)
- Python
- Kotlin Compiler (`kotlinc`) – [Download](https://kotlinlang.org/docs/releases.html)
- Java Runtime (JDK 17+ recommended)
- ADB (Android Debug Bridge) – Included with [Android Studio](https://developer.android.com/studio)

### 📱 Client-Side (Phone)
- Android 9 or above
- USB Debugging enabled (Developer Options)

---

## ⚙️ Setup Instructions

### 1. Clone the Project to yout PC
```bash
git clone https://github.com/cptdihindu/TextEditorApp-MAD.git
cd TextEditorApp-MAD
```

**Project Structure**:
```
/App/       → Android Studio project (Kotlin + Jetpack Compose)
/Server/    → Flask server (server.py)
/Builds/    → Installable APK
```
2. Install the APK from `Builds/KTextEdit-v1.0.apk` into your phone
  
### 2. Configure & Run the Server (PC)

1. Install Python dependencies:
   ```bash
   pip install flask colorama
   ```
2. Ensure `kotlinc` and `java` are in your System variable PATHs:
     
3. Start the Flask server:
   ```bash
   cd Server
   python server.py
   ```
   The server will run at `http://127.0.0.1:8081/compile`.

### 3. Connect Phone via ADB

1. Enable **USB Debugging** in Developer Options on your phone. (if not done already)

2. Connect your phone via USB.

3. Verify the device is detected:
   ```bash
   adb devices
   ```
4. Allow USB debugging in your phone if prompted

5. Enable reverse proxy to map the phone’s `127.0.0.1:8081` to the PC server:
   ```bash
   adb reverse tcp:8081 tcp:8081
   ```

### 4. Run the app and Compile

1. Open the installed TextEditor app on your phone.

2. Type a kotlin code in the editor and save with ".kt" extention. Or load an existing kotlin file.

3. Press the **▶ Compile** button to see output or errors in the results panel.

---

## 📹 Demo
*(Insert link to your demo video here)*

---

## 👥 Contributions

- **H D D H Liyanage - 23020504**: Editor core, undo/redo, file handling
- **H D D H Liyanage - 23020504**: Syntax highlighting, JSON config
- **H D D H Liyanage - 23020504**: Compiler bridge, Flask server, ADB setup

---


## ⚠️ Notes

- App will only show the outputs/erros in the code. that means no user interactions while program running 
