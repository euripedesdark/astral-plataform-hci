document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('loginForm');
    const submitBtn = document.getElementById('submitBtn');
    const errorMsg = document.getElementById('errorMsg');

    form.addEventListener('submit', async (e) => {
        e.preventDefault();

        const username = document.getElementById('username').value;
        const password = document.getElementById('password').value;

        submitBtn.textContent = 'AUTHENTICATING...';
        submitBtn.style.pointerEvents = 'none';
        errorMsg.style.display = 'none';

        try {
            // Requisição para a API (O Nginx roteia isso para o Spring Boot no 127.0.0.1:8081)
            const response = await fetch('/api/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });

            const data = await response.json();

            if (response.ok && data.token) {
                // Autenticação aceita: guarda o token JWT como COOKIE para o Spring Boot ler no redirecionamento
                document.cookie = "astral_token=" + data.token + "; path=/; max-age=86400";
                window.location.href = '/inicio';
            } else {
                throw new Error(data.message || 'Acesso negado');
            }

        } catch (error) {
            console.error('Falha de Autenticação:', error);
            errorMsg.style.display = 'block';
            submitBtn.textContent = 'SIGN IN';
            submitBtn.style.pointerEvents = 'auto';
        }
    });
});
