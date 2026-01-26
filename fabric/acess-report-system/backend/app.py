from flask import Flask, render_template, jsonify, request
from flask_sqlalchemy import SQLAlchemy
from flask_cors import CORS
from apscheduler.schedulers.background import BackgroundScheduler
from config import Config
import os
import socket
import getpass
from datetime import datetime, timedelta
from models import AccessRecord, db
from collector import coletar_e_salvar_acessos

app = Flask(__name__,
    template_folder='/opt/acess-report-system/frontend/templates',
    static_folder='/opt/acess-report-system/frontend/static'
)

app.config.from_object(Config())
CORS(app)

# Inicializar SQLAlchemy
db.init_app(app)

# Scheduler para atualizações automáticas
scheduler = BackgroundScheduler()

def init_scheduler():
    """Inicializar tarefas periódicas"""
    if not scheduler.running:
        # Coletar dados a cada 2 minutos
        scheduler.add_job(coletar_e_salvar_acessos, 'interval', minutes=2, id='chrome_collector')
        scheduler.start()
        print("✓ Scheduler iniciado - coletando dados a cada 2 minutos")

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/api/access-records')
def get_access_records():
    # Parâmetros de filtro
    start_date = request.args.get('start_date')
    end_date = request.args.get('end_date')
    category = request.args.get('category', 'all')
    user = request.args.get('user', 'all')
    
    query = AccessRecord.query.order_by(AccessRecord.timestamp.desc())
    
    if start_date:
        start_dt = datetime.strptime(start_date, '%Y-%m-%d')
        query = query.filter(AccessRecord.timestamp >= start_dt)
    if end_date:
        end_dt = datetime.strptime(end_date, '%Y-%m-%d') + timedelta(days=1)
        query = query.filter(AccessRecord.timestamp < end_dt)
    if category and category != 'all':
        query = query.filter(AccessRecord.category == category)
    if user and user != 'all':
        query = query.filter(AccessRecord.user_name == user)
    
    records = query.limit(100).all()
    return jsonify([record.to_dict() for record in records])

@app.route('/api/categories')
def get_categories():
    categories = db.session.query(AccessRecord.category).distinct().all()
    return jsonify([cat[0] for cat in categories])

@app.route('/api/users')
def get_users():
    users = db.session.query(AccessRecord.user_name).distinct().all()
    return jsonify([user[0] for user in users])

@app.route('/api/stats')
def get_stats():
    total_accesses = AccessRecord.query.count()
    today = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    today_accesses = AccessRecord.query.filter(AccessRecord.timestamp >= today).count()
    unique_categories = db.session.query(AccessRecord.category).distinct().count()
    last_24h = datetime.now() - timedelta(hours=24)
    active_users = db.session.query(AccessRecord.user_name).distinct().filter(
        AccessRecord.timestamp >= last_24h
    ).count()
    
    return jsonify({
        'total_accesses': total_accesses,
        'today_accesses': today_accesses,
        'unique_categories': unique_categories,
        'active_users': active_users,
        'last_update': datetime.now().strftime('%d/%m/%Y %H:%M:%S')
    })

def create_app():
    """Inicializar aplicação e banco de dados"""
    with app.app_context():
        # Criar tabelas se não existirem
        db.create_all()
        # Inicializar scheduler
        init_scheduler()
    return app
