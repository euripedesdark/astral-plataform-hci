from flask_sqlalchemy import SQLAlchemy
from datetime import datetime

db = SQLAlchemy()

class AccessRecord(db.Model):
    __tablename__ = 'access_records'
    id = db.Column(db.BigInteger, primary_key=True)
    timestamp = db.Column(db.DateTime, nullable=False, index=True)
    user_name = db.Column(db.String(100), nullable=False, index=True)
    hostname = db.Column(db.String(100), nullable=False)
    ip_address = db.Column(db.String(45), nullable=False)
    url = db.Column(db.Text, nullable=False)
    title = db.Column(db.Text)
    category = db.Column(db.String(100), nullable=False, index=True)
    domain = db.Column(db.String(255), nullable=False, index=True)
    is_blacklisted = db.Column(db.Boolean, default=False)
    created_at = db.Column(db.DateTime, default=datetime.utcnow, index=True)  # Para limpeza automática

    def to_dict(self):
        return {
            'id': self.id,
            'timestamp': self.timestamp.strftime('%Y-%m-%d %H:%M:%S'),
            'user': self.user_name,
            'hostname': self.hostname,
            'ip_address': self.ip_address,
            'url': self.url,
            'title': self.title or 'Sem título',
            'category': self.category,
            'domain': self.domain
        }
