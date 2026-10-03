# Vehicle Checker 🚗🔍

**Vehicle Checker** is a feature-rich, modern Android application designed for UK vehicle buyers, owners, and automotive enthusiasts. It provides instant access to vehicle specifications, complete MOT history, tax status, running cost estimates, component health scoring, ownership risk indicators, and AI-powered mechanic insights.

---

## ✨ Key Features

### 🔍 Instant Vehicle Lookup & Optical Plate Scanner
- **Registration Search**: Search any UK vehicle registration plate to retrieve detailed vehicle specifications.
- **Camera Plate Scanner**: Scan license plates using the device camera powered by **Google ML Kit Text Recognition** and **CameraX**.
- **Comprehensive Vehicle Specs**: Includes Make, Colour, Fuel Type, Engine Capacity, CO2 Emissions, Year of Manufacture, First Registered Date, Wheelplan, Revenue Weight, Export Status, and V5C Logbook Issue Date.

---

### 📜 MOT History & Visual Analytics
- **Detailed MOT Test Records**: View full test history including Pass/Fail outcomes, test dates, test numbers, odometer mileage readings, and detailed failure/advisory items.
- **MOT Pass/Fail Distribution**: Visual pie chart displaying overall pass vs. fail rates across the vehicle's lifespan.
- **Mileage Progression Chart**: Interactive bar chart tracking mileage recorded during MOT tests to identify mileage trends, annual averages, and flag potential rollback anomalies.
- **Dedicated MOT History Screen**: Fast, offline-first MOT screen with instant cached loading.

---

### 🛠️ Smart Health & Risk Analysis
- **Component Health Calculator**: Scores 5 key vehicle systems from 0–100% based on historical MOT failure and advisory patterns, categorising risk with color-coded ratings (Green, Amber, Red):
  - 🛑 **Brakes**
  - ⚙️ **Suspension & Steering**
  - 🛞 **Tyres & Wheels**
  - 💨 **Exhaust & Emissions**
  - ⚡ **Structure & Electrics**
- **Keeper History & Ownership Retention**: Analyzes DVLA V5C logbook issue dates to estimate overall vehicle age vs. current keeper duration, calculating ownership retention percentages and flagging short-term turnover or recent logbook reissues.
- **Finance & Ownership Risk Checks**: Evaluates V5C issue dates, export markers, and ownership stability signals to provide buyer risk alerts and direct access to financial register checks.

---

### 💸 Tax, Environmental & Cost Calculators
- **Road Tax (VED) Estimator**: Calculates 12-month UK Vehicle Excise Duty (road tax) rates based on registration date, CO2 emissions, engine size, and fuel type (incorporating latest UK tax bands and EV regulations).
- **ULEZ & Clean Air Zone Compliance**: Checks London ULEZ and UK Clean Air Zone compliance based on Euro emissions standards, fuel type, and registration age.
- **Annual Running Cost Calculator**: Combines real MOT annual mileage trends with fuel efficiency estimates and VED road tax to compute total annual running costs and pence-per-mile figures.

---

### 🤖 AI Mechanic Analyst
- **Gemini-Powered Analysis**: Integrated **Google Generative AI (Gemini)** model that streams real-time mechanic insights on vehicle condition, potential future issues, model-specific buyer advice, and MOT history breakdowns.

---

### 📋 Personal Vehicle Management
- **Offline Caching**: Offline-first architecture for instant loading of previously checked vehicles and MOT records.
- **Recent Searches & Favourites**: Quick access to search history with favourite vehicle bookmarking.
- **Private Vehicle Notes**: Attach personal notes to any vehicle record saved locally on device.
- **Service & Maintenance Log**: Keep private service and repair records including date, mileage, work description, and cost per vehicle.
- **Tax & MOT Expiry Reminders**: Background notification scheduler using **Android WorkManager** that alerts users before MOT or Road Tax expires, supported by periodic background status refreshes.

---

### 📄 PDF Export & Sharing
- **Comprehensive PDF Reports**: Generates multi-page PDF summary reports containing vehicle details, tax status, MOT history, and cost breakdowns.
- **Easy Sharing**: Share generated PDF reports directly via standard Android system sharing.

---

## 🛠️ Architecture & Tech Stack

- **Language**: Kotlin
- **Architecture**: Modern Android architecture with offline-first caching and repository design pattern
- **UI Framework**: Android XML Layouts, Material Components, Edge-to-Edge window handling, Custom Views
- **Database**: **Room Database** (Entities: Vehicles, Notes, Cached Vehicles, Service Logs) with automatic schema migrations
- **Background Tasks**: **WorkManager** for scheduled daily expiry checks and periodic status updates
- **Computer Vision**: **CameraX** + **Google ML Kit Text Recognition**
- **AI Integration**: **Google Generative AI SDK (Gemini Flash)** with streaming response rendering
- **PDF & Formatting**: Built-in Android `PdfDocument` engine & **Markwon** Markdown renderer
- **Testing**: JUnit unit test coverage for health scoring, tax estimators, running cost logic, and repository synchronization

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug (2024.2.1) or newer
- Android SDK 26 (Android 8.0) or higher
- JDK 17

### Building the Project
1. Clone the repository:
   ```bash
   git clone https://github.com/your-repo/VehicleChecker.git
   cd VehicleChecker
   ```
2. Open the project in Android Studio.
3. Build the project using Gradle:
   ```bash
   ./gradlew assembleDebug
   ```
4. Run the app on an Android emulator or connected device.

---

## 🔒 Privacy & Permissions
- **Camera (`android.permission.CAMERA`)**: Used solely for live optical number plate scanning.
- **Notifications (`android.permission.POST_NOTIFICATIONS`)**: Used for scheduling tax and MOT expiry reminder alerts.
- **Data Storage**: All user notes, service logs, and search history are stored locally on device using an encrypted local SQLite/Room database.
