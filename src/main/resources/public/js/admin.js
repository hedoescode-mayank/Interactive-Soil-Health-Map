/**
 * Admin Portal JavaScript
 * Handles admin authentication, password toggle, theme, and UI interactions
 */

document.addEventListener('DOMContentLoaded', () => {
    initAdminTheme();
    initAdminMobileMenu();
    checkAdminSession();
});

// ===== Theme Management =====
function initAdminTheme() {
    const savedTheme = localStorage.getItem('theme') || 'light';
    document.documentElement.setAttribute('data-theme', savedTheme);
    updateAdminThemeIcon(savedTheme);

    const themeToggle = document.getElementById('themeToggle');
    if (themeToggle) {
        themeToggle.addEventListener('click', toggleAdminTheme);
    }
}

function toggleAdminTheme() {
    const currentTheme = document.documentElement.getAttribute('data-theme');
    const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', newTheme);
    localStorage.setItem('theme', newTheme);
    updateAdminThemeIcon(newTheme);
}

function updateAdminThemeIcon(theme) {
    const toggleBtn = document.getElementById('themeToggle');
    if (toggleBtn) {
        toggleBtn.textContent = theme === 'dark' ? '☀️' : '🌙';
    }
}

// ===== Mobile Menu =====
function initAdminMobileMenu() {
    const menuToggle = document.getElementById('menuToggle');
    const nav = document.querySelector('.nav');

    if (menuToggle && nav) {
        menuToggle.addEventListener('click', () => {
            nav.classList.toggle('active');
            menuToggle.textContent = nav.classList.contains('active') ? '✕' : '☰';
        });
    }
}

// ===== Password Toggle =====
function toggleAdminPassword() {
    const passwordInput = document.getElementById('adminPassword');
    const eyeIcon = document.getElementById('eyeIcon');

    if (passwordInput.type === 'password') {
        passwordInput.type = 'text';
        eyeIcon.innerHTML = `
            <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>
            <line x1="1" y1="1" x2="23" y2="23"/>
        `;
    } else {
        passwordInput.type = 'password';
        eyeIcon.innerHTML = `
            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
            <circle cx="12" cy="12" r="3"/>
        `;
    }
}

// ===== Admin Login Handler =====
async function handleAdminLogin(e) {
    e.preventDefault();

    const username = document.getElementById('adminUsername').value.trim();
    const password = document.getElementById('adminPassword').value;
    const submitBtn = document.getElementById('adminSubmitBtn');
    const btnText = submitBtn.querySelector('.btn-text');
    const btnLoader = submitBtn.querySelector('.btn-loader');
    const btnArrow = submitBtn.querySelector('.btn-arrow');

    // Show loading state
    btnText.textContent = 'Signing in...';
    btnLoader.style.display = 'flex';
    btnArrow.style.display = 'none';
    submitBtn.disabled = true;

    try {
        const response = await fetch('/api/auth/admin/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, password })
        });

        const data = await response.json();

        if (data.success) {
            localStorage.setItem('adminToken', data.token);
            localStorage.setItem('adminUser', JSON.stringify(data.admin));

            // Success animation
            submitBtn.style.background = 'linear-gradient(135deg, #10b981, #059669)';
            btnText.textContent = 'Welcome!';
            btnLoader.style.display = 'none';

            showAdminToast('Login successful! Redirecting...', 'success');

            setTimeout(() => {
                window.location.href = '/admin/dashboard';
            }, 1200);
        } else {
            showAdminToast(data.error || 'Invalid admin credentials', 'error');
            shakeCard();
            resetLoginBtn();
        }
    } catch (error) {
        console.error('Admin login error:', error);
        showAdminToast('Connection error. Please try again.', 'error');
        shakeCard();
        resetLoginBtn();
    }
}

// ===== Reset Login Button =====
function resetLoginBtn() {
    const submitBtn = document.getElementById('adminSubmitBtn');
    const btnText = submitBtn.querySelector('.btn-text');
    const btnLoader = submitBtn.querySelector('.btn-loader');
    const btnArrow = submitBtn.querySelector('.btn-arrow');

    submitBtn.disabled = false;
    submitBtn.style.background = '';
    btnText.textContent = 'Sign In';
    btnLoader.style.display = 'none';
    btnArrow.style.display = '';
}

// ===== Card Shake Animation =====
function shakeCard() {
    const card = document.getElementById('adminLoginCard');
    card.style.animation = 'none';
    card.offsetHeight; // force reflow
    card.style.animation = 'shakeCard 0.5s ease';
}

// ===== Toast Notifications =====
function showAdminToast(message, type = 'info') {
    // Remove existing toasts
    document.querySelectorAll('.admin-toast').forEach(t => t.remove());

    const toast = document.createElement('div');
    toast.className = `admin-toast admin-toast-${type}`;

    const icons = {
        success: '✓',
        error: '✕',
        info: 'ℹ',
        warning: '⚠'
    };

    toast.innerHTML = `
        <span class="toast-icon">${icons[type] || icons.info}</span>
        <span class="toast-message">${message}</span>
    `;

    document.body.appendChild(toast);

    // Trigger animation
    requestAnimationFrame(() => {
        toast.classList.add('show');
    });

    // Auto dismiss
    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 300);
    }, 4000);
}

// ===== Check Existing Admin Session =====
function checkAdminSession() {
    const adminToken = localStorage.getItem('adminToken');
    if (adminToken) {
        showDashboardPreview();
    }
}

// ===== Show Dashboard Preview =====
function showDashboardPreview() {
    const loginSection = document.getElementById('adminLoginSection');
    const dashboard = document.getElementById('adminDashboard');
    const adminUser = JSON.parse(localStorage.getItem('adminUser') || '{}');

    if (loginSection) loginSection.style.display = 'none';
    if (dashboard) {
        dashboard.style.display = 'block';
        const nameDisplay = document.getElementById('adminNameDisplay');
        if (nameDisplay && adminUser.name) {
            nameDisplay.textContent = adminUser.name;
        }
    }
}

// ===== Admin Logout =====
function handleAdminLogout() {
    localStorage.removeItem('adminToken');
    localStorage.removeItem('adminUser');
    showAdminToast('Logged out successfully', 'info');
    setTimeout(() => {
        window.location.reload();
    }, 800);
}
