#!/usr/bin/env bash
# run_server.sh — one-shot setup & launch for the Network Cell Analyzer server
# Works on: Windows (Git Bash / WSL), macOS, Linux
set -e

echo "========================================="
echo " Network Cell Analyzer Server"
echo " EECE 451 - AUB Spring 2026"
echo "========================================="

# Create virtualenv if it doesn't exist
if [ ! -d "venv" ]; then
  echo "[*] Creating Python virtual environment..."
  python -m venv venv
fi

# Activate — handle Windows (Scripts) vs Unix (bin)
if [ -f "venv/Scripts/activate" ]; then
  source venv/Scripts/activate       # Windows Git Bash / WSL
elif [ -f "venv/bin/activate" ]; then
  source venv/bin/activate            # macOS / Linux
else
  echo "[!] Could not find venv activation script. Run manually:"
  echo "    source venv/bin/activate   (or venv\\Scripts\\activate on Windows)"
  exit 1
fi

echo "[*] Installing dependencies..."
pip install -q -r requirements.txt

# Print local IP so students can copy it into the Android app Settings
echo ""
echo "========================================="
echo " Server ready."
echo " Android app settings:"
if command -v ipconfig &>/dev/null; then
  # Windows
  LOCAL_IP=$(ipconfig | grep -oP '(?<=IPv4 Address[. ]{1,20}: )[\d.]+' | head -1)
else
  # macOS / Linux
  LOCAL_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
fi
echo "   Host: ${LOCAL_IP:-<your local IP>}"
echo "   Port: 5000"
echo " Dashboard: http://localhost:5000/"
echo " Press Ctrl+C to stop."
echo "========================================="
echo ""

python app.py
