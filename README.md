# Network Cell Analyzer

An Android and Flask-based network cell analyzer that collects mobile signal reports from an Android device and visualizes network performance through a web dashboard.

## Project Overview

This project includes two main parts:

- An Android mobile application that collects cellular network information from the device.
- A Flask server that receives reports from the Android app and displays the collected data on a dashboard.

The goal of the project is to monitor mobile network conditions and provide a simple way to view network signal reports and performance-related information.

## Technologies Used

- Kotlin
- Android Studio
- Flask
- Python
- HTML
- REST API
- Mobile Network Monitoring

## Features

- Collects cellular network information from an Android device
- Sends network reports from the mobile app to a Flask backend
- Stores and processes received network data on the server
- Displays collected reports through a web dashboard
- Separates the system into mobile and server-side components

## Repository Structure

```text
network-cell-analyzer/
│
├── android-app/
│   └── MyApplication2/
│       └── Android application source code
│
├── server/
│   ├── app.py
│   ├── requirements.txt
│   ├── run_server.sh
│   └── templates/
│       └── dashboard.html
│
├── .gitignore
└── README.md
```
