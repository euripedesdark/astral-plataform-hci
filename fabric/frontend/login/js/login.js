document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('loginForm');
    const submitBtn = document.getElementById('submitBtn');
    const errorMsg = document.getElementById('errorMsg');
    if (!form) { console.error('id="loginForm" não encontrado no index.html'); return; }

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const username = document.getElementById('username').value;
        const password = document.getElementById('password').value;
        if (submitBtn) { submitBtn.textContent = 'AUTHENTICATING...'; submitBtn.style.pointerEvents = 'none'; }
        if (errorMsg) errorMsg.style.display = 'none';
                          try {
                              const response = await fetch('/api/auth/login', {
                                  method: 'POST',
                                  headers: { 'Content-Type': 'application/json' },
                                  body: JSON.stringify({ username, password })
                              });
                              const data = await response.json();
                              if (response.ok && data.token) {
                                  // O AuthFilter lê COOKIE, não localStorage:
                                  document.cookie = 'astral_token=' + encodeURIComponent(data.token) + '; path=/';
                                  localStorage.setItem('astral_token', data.token);
                                  window.location.href = '/inicio';
                              } else {
                                  throw new Error(data.message || 'Acesso negado');
                              }
                          } catch (error) {
                              console.error('Falha de Autenticação:', error);
                              if (errorMsg) errorMsg.style.display = 'block';
                          if (submitBtn) { submitBtn.textContent = 'SIGN IN'; submitBtn.style.pointerEvents = 'auto'; }
                          }
    });
});
