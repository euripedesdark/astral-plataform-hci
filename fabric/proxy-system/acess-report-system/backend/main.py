#!/usr/bin/env python3
import sys
import os

# Adicionar diretórios ao PYTHONPATH
sys.path.insert(0, '/opt/acess-report-system')
sys.path.insert(0, '/opt/acess-report-system/backend')

from app import create_app

if __name__ == "__main__":
    app = create_app()
    app.run(host='0.0.0.0', port=5000, debug=False)
