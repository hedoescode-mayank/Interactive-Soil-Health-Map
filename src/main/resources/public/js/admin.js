/**
 * Admin Portal JavaScript
 * Complete backend integration for admin authentication, dashboard stats,
 * farmer management, soil data, reports, and audit logs.
 */

// ===== State =====
let currentFarmerPage = 1;
let currentSoilDataPage = 1;
let currentAuditPage = 1;
const PAGE_SIZE = 15;

document.addEventListener('DOMContentLoaded', () => {
    initAdminTheme();
    initAdminMobileMenu();
    checkAdminSession();
    initSearchListeners();
    console.log("Admin Portal Initialized successfully.");
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

// ===== Search Listeners =====
function initSearchListeners() {
    const farmerSearch = document.getElementById('farmerSearchInput');
    if (farmerSearch) {
        let debounce;
        farmerSearch.addEventListener('input', () => {
            clearTimeout(debounce);
            debounce = setTimeout(() => {
                currentFarmerPage = 1;
                loadFarmers();
            }, 400);
        });
    }

    const soilFilter = document.getElementById('soilDistrictFilter');
    if (soilFilter) {
        let debounce;
        soilFilter.addEventListener('input', () => {
            clearTimeout(debounce);
            debounce = setTimeout(() => {
                currentSoilDataPage = 1;
                loadSoilData();
            }, 400);
        });
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

            showAdminToast('Login successful! Loading dashboard...', 'success');

            setTimeout(() => {
                showDashboardPreview();
                loadDashboardStats();
            }, 800);
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
        loadDashboardStats();
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
        dashboard.style.animation = 'fadeInUp 0.6s ease';
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

// ===== API Helper =====
function getAdminHeaders() {
    const token = localStorage.getItem('adminToken');
    return {
        'Content-Type': 'application/json',
        'Authorization': token ? `Bearer ${token}` : ''
    };
}

async function adminFetch(url, options = {}) {
    const response = await fetch(url, {
        ...options,
        headers: { ...getAdminHeaders(), ...(options.headers || {}) }
    });
    return response.json();
}

// ==========================================
// DASHBOARD STATS
// ==========================================

async function loadDashboardStats() {
    try {
        const data = await adminFetch('/api/admin/stats');

        if (data.success) {
            // Animate stat values
            animateValue('statFarmersValue', data.totalFarmers || 0);
            animateValue('statSamplesValue', data.totalSoilSamples || 0);
            animateValue('statCardsValue', data.totalSHCGenerated || 0);
            animateValue('statDistrictsValue', data.districtsCovered || 0);

            // Update trends
            const recentPct = data.totalFarmers > 0 
                ? ((data.recentRegistrations / data.totalFarmers) * 100).toFixed(1) 
                : 0;
            updateTrend('statFarmersTrend', recentPct);
            updateTrend('statSamplesTrend', data.totalSoilSamples > 0 ? '12.5' : '0');
            updateTrend('statCardsTrend', data.totalSHCGenerated > 0 ? '5.7' : '0');
        }
    } catch (error) {
        console.error('Failed to load dashboard stats:', error);
    }
}

function animateValue(elementId, endVal) {
    const el = document.getElementById(elementId);
    if (!el) return;

    const duration = 1200;
    const start = performance.now();
    const startVal = 0;

    function update(now) {
        const progress = Math.min((now - start) / duration, 1);
        const eased = 1 - Math.pow(1 - progress, 3); // ease-out cubic
        const current = Math.floor(startVal + (endVal - startVal) * eased);
        el.textContent = current.toLocaleString();
        if (progress < 1) requestAnimationFrame(update);
    }

    requestAnimationFrame(update);
}

function updateTrend(elementId, value) {
    const el = document.getElementById(elementId);
    if (!el) return;

    const num = parseFloat(value);
    if (num > 0) {
        el.textContent = `↑ ${num}%`;
        el.className = 'stat-trend positive';
    } else if (num < 0) {
        el.textContent = `↓ ${Math.abs(num)}%`;
        el.className = 'stat-trend negative';
    } else {
        el.textContent = `→ 0%`;
        el.className = 'stat-trend neutral';
    }
}

// ==========================================
// PANEL MANAGEMENT
// ==========================================

function showAdminPanel(panel) {
    // Hide all panels first
    document.querySelectorAll('.admin-panel').forEach(p => p.style.display = 'none');

    const panelId = `panel${panel.charAt(0).toUpperCase() + panel.slice(1)}`;
    const panelEl = document.getElementById(panelId);
    if (panelEl) {
        panelEl.style.display = 'block';
        panelEl.style.animation = 'fadeInUp 0.4s ease';

        // Load data for the panel
        switch (panel) {
            case 'users': loadFarmers(); break;
            case 'reports': loadReports(); break;
            case 'soildata': loadSoilData(); break;
            case 'audit': loadAuditLogs(); break;
        }

        // Scroll to panel
        panelEl.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
}

function closeAdminPanel(panel) {
    const panelId = `panel${panel.charAt(0).toUpperCase() + panel.slice(1)}`;
    const panelEl = document.getElementById(panelId);
    if (panelEl) {
        panelEl.style.animation = 'fadeOut 0.3s ease';
        setTimeout(() => {
            panelEl.style.display = 'none';
        }, 300);
    }
}

// ==========================================
// FARMER MANAGEMENT
// ==========================================

async function loadFarmers() {
    const search = document.getElementById('farmerSearchInput')?.value || '';
    const tbody = document.getElementById('farmersTableBody');
    tbody.innerHTML = '<tr><td colspan="9" class="loading-cell">Loading farmers...</td></tr>';

    try {
        const data = await adminFetch(
            `/api/admin/farmers?page=${currentFarmerPage}&size=${PAGE_SIZE}&search=${encodeURIComponent(search)}`
        );

        if (data.success && data.farmers) {
            if (data.farmers.length === 0) {
                tbody.innerHTML = '<tr><td colspan="9" class="loading-cell">No farmers found</td></tr>';
            } else {
                tbody.innerHTML = data.farmers.map(f => `
                    <tr class="${!f.isActive ? 'inactive-row' : ''}">
                        <td>${f.id}</td>
                        <td><strong>${escapeHtml(f.username)}</strong></td>
                        <td>${escapeHtml(f.fullName || '—')}</td>
                        <td>${escapeHtml(f.phone || '—')}</td>
                        <td>${escapeHtml(f.district || '—')}</td>
                        <td>${escapeHtml(f.state || '—')}</td>
                        <td><span class="status-badge ${f.isActive ? 'active' : 'inactive'}">${f.isActive ? 'Active' : 'Inactive'}</span></td>
                        <td>${f.lastLogin ? formatDate(f.lastLogin) : 'Never'}</td>
                        <td class="action-cell">
                            <button onclick="editFarmer(${f.id})" class="table-btn edit" title="Edit">✏️</button>
                            ${f.isActive ? `<button onclick="deactivateFarmer(${f.id})" class="table-btn delete" title="Deactivate">🚫</button>` : ''}
                        </td>
                    </tr>
                `).join('');
            }

            renderPagination('farmersPagination', data.page, data.totalPages, (p) => {
                currentFarmerPage = p;
                loadFarmers();
            });
        }
    } catch (error) {
        console.error('Failed to load farmers:', error);
        tbody.innerHTML = '<tr><td colspan="9" class="loading-cell error">Failed to load farmers</td></tr>';
    }
}

async function editFarmer(farmerId) {
    try {
        const data = await adminFetch(`/api/admin/farmers/${farmerId}`);
        if (data.success) {
            document.getElementById('editFarmerId').value = data.id;
            document.getElementById('editFullName').value = data.fullName || '';
            document.getElementById('editPhone').value = data.phone || '';
            document.getElementById('editIsActive').value = data.isActive ? 'true' : 'false';
            document.getElementById('farmerEditModal').style.display = 'flex';
        }
    } catch (error) {
        showAdminToast('Failed to load farmer details', 'error');
    }
}

function closeFarmerModal() {
    document.getElementById('farmerEditModal').style.display = 'none';
}

async function saveFarmerEdit(e) {
    e.preventDefault();
    const farmerId = document.getElementById('editFarmerId').value;
    const body = {
        fullName: document.getElementById('editFullName').value,
        phone: document.getElementById('editPhone').value,
        isActive: document.getElementById('editIsActive').value === 'true'
    };

    try {
        const data = await adminFetch(`/api/admin/farmers/${farmerId}`, {
            method: 'PUT',
            body: JSON.stringify(body)
        });

        if (data.success) {
            showAdminToast('Farmer updated successfully', 'success');
            closeFarmerModal();
            loadFarmers();
        } else {
            showAdminToast(data.error || 'Update failed', 'error');
        }
    } catch (error) {
        showAdminToast('Failed to update farmer', 'error');
    }
}

async function deactivateFarmer(farmerId) {
    if (!confirm('Are you sure you want to deactivate this farmer?')) return;

    try {
        const data = await adminFetch(`/api/admin/farmers/${farmerId}`, { method: 'DELETE' });
        if (data.success) {
            showAdminToast('Farmer deactivated', 'success');
            loadFarmers();
        } else {
            showAdminToast(data.error || 'Failed to deactivate', 'error');
        }
    } catch (error) {
        showAdminToast('Failed to deactivate farmer', 'error');
    }
}

// ==========================================
// REPORTS
// ==========================================

async function loadReports() {
    try {
        const data = await adminFetch('/api/admin/reports/summary');

        if (data.success) {
            // Render NPK distribution bars
            renderNPKBars('nitrogenBars', data.nitrogenDistribution, 'Nitrogen');
            renderNPKBars('phosphorusBars', data.phosphorusDistribution, 'Phosphorus');
            renderNPKBars('potassiumBars', data.potassiumDistribution, 'Potassium');

            // Render district stats
            const distBody = document.getElementById('districtStatsBody');
            if (data.districtStats && data.districtStats.length > 0) {
                distBody.innerHTML = data.districtStats.map(d => `
                    <tr>
                        <td><strong>${escapeHtml(d.district)}</strong></td>
                        <td>${d.totalFarms}</td>
                        <td>${(d.avgNitrogen || 0).toFixed(1)}</td>
                        <td>${(d.avgPhosphorus || 0).toFixed(1)}</td>
                        <td>${(d.avgPotassium || 0).toFixed(1)}</td>
                        <td>${(d.avgPH || 0).toFixed(2)}</td>
                        <td>${(d.avgOrganicCarbon || 0).toFixed(2)}</td>
                    </tr>
                `).join('');
            } else {
                distBody.innerHTML = '<tr><td colspan="7" class="loading-cell">No district data available</td></tr>';
            }

            // Render crop stats
            const cropBody = document.getElementById('cropStatsBody');
            if (data.cropStats && data.cropStats.length > 0) {
                cropBody.innerHTML = data.cropStats.map(c => `
                    <tr>
                        <td><strong>${escapeHtml(c.cropName)}</strong></td>
                        <td>${c.recommendationCount}</td>
                        <td>${(c.avgUrea || 0).toFixed(1)}</td>
                        <td>${(c.avgDAP || 0).toFixed(1)}</td>
                        <td>${(c.avgMOP || 0).toFixed(1)}</td>
                    </tr>
                `).join('');
            } else {
                cropBody.innerHTML = '<tr><td colspan="5" class="loading-cell">No crop data available</td></tr>';
            }
        }
    } catch (error) {
        console.error('Failed to load reports:', error);
        showAdminToast('Failed to load reports', 'error');
    }
}

function renderNPKBars(containerId, distribution, label) {
    const container = document.getElementById(containerId);
    if (!container || !distribution) return;

    const total = (distribution.low || 0) + (distribution.medium || 0) + (distribution.high || 0);
    if (total === 0) {
        container.innerHTML = '<p class="no-data">No data available</p>';
        return;
    }

    const lowPct = ((distribution.low / total) * 100).toFixed(1);
    const medPct = ((distribution.medium / total) * 100).toFixed(1);
    const highPct = ((distribution.high / total) * 100).toFixed(1);

    container.innerHTML = `
        <div class="status-bar-row">
            <span class="bar-label">Low</span>
            <div class="bar-track"><div class="bar-fill low" style="width: ${lowPct}%"></div></div>
            <span class="bar-value">${distribution.low} (${lowPct}%)</span>
        </div>
        <div class="status-bar-row">
            <span class="bar-label">Medium</span>
            <div class="bar-track"><div class="bar-fill medium" style="width: ${medPct}%"></div></div>
            <span class="bar-value">${distribution.medium} (${medPct}%)</span>
        </div>
        <div class="status-bar-row">
            <span class="bar-label">High</span>
            <div class="bar-track"><div class="bar-fill high" style="width: ${highPct}%"></div></div>
            <span class="bar-value">${distribution.high} (${highPct}%)</span>
        </div>
    `;
}

// ==========================================
// SOIL DATA
// ==========================================

async function loadSoilData() {
    const district = document.getElementById('soilDistrictFilter')?.value || '';
    const tbody = document.getElementById('soilDataBody');
    tbody.innerHTML = '<tr><td colspan="10" class="loading-cell">Loading soil data...</td></tr>';

    try {
        const data = await adminFetch(
            `/api/admin/soil-data?page=${currentSoilDataPage}&size=${PAGE_SIZE}&district=${encodeURIComponent(district)}`
        );

        if (data.success && data.data) {
            if (data.data.length === 0) {
                tbody.innerHTML = '<tr><td colspan="10" class="loading-cell">No soil data found</td></tr>';
            } else {
                tbody.innerHTML = data.data.map(s => `
                    <tr>
                        <td>${s.testId}</td>
                        <td>${escapeHtml(s.farmerName || s.username)}</td>
                        <td>${escapeHtml(s.district || '—')}</td>
                        <td>${s.testDate ? formatDate(s.testDate) : '—'}</td>
                        <td class="${getNPKClass('n', s.nitrogen)}">${(s.nitrogen || 0).toFixed(1)}</td>
                        <td class="${getNPKClass('p', s.phosphorus)}">${(s.phosphorus || 0).toFixed(1)}</td>
                        <td class="${getNPKClass('k', s.potassium)}">${(s.potassium || 0).toFixed(1)}</td>
                        <td>${(s.ph || 0).toFixed(2)}</td>
                        <td>${(s.organicCarbon || 0).toFixed(2)}</td>
                        <td class="action-cell">
                            <button onclick="deleteSoilRecord(${s.testId})" class="table-btn delete" title="Delete">🗑️</button>
                        </td>
                    </tr>
                `).join('');
            }

            renderPagination('soilDataPagination', data.page, data.totalPages, (p) => {
                currentSoilDataPage = p;
                loadSoilData();
            });
        }
    } catch (error) {
        console.error('Failed to load soil data:', error);
        tbody.innerHTML = '<tr><td colspan="10" class="loading-cell error">Failed to load soil data</td></tr>';
    }
}

async function deleteSoilRecord(testId) {
    if (!confirm('Are you sure you want to delete this soil test record?')) return;

    try {
        const data = await adminFetch(`/api/admin/soil-data/${testId}`, { method: 'DELETE' });
        if (data.success) {
            showAdminToast('Soil test record deleted', 'success');
            loadSoilData();
        } else {
            showAdminToast(data.error || 'Failed to delete', 'error');
        }
    } catch (error) {
        showAdminToast('Failed to delete soil test', 'error');
    }
}

// ==========================================
// AUDIT LOGS
// ==========================================

async function loadAuditLogs() {
    const tbody = document.getElementById('auditLogsBody');
    tbody.innerHTML = '<tr><td colspan="6" class="loading-cell">Loading audit logs...</td></tr>';

    try {
        const data = await adminFetch(`/api/admin/audit-logs?page=${currentAuditPage}&size=${PAGE_SIZE}`);

        if (data.success && data.logs) {
            if (data.logs.length === 0) {
                tbody.innerHTML = '<tr><td colspan="6" class="loading-cell">No audit logs found</td></tr>';
            } else {
                tbody.innerHTML = data.logs.map(l => `
                    <tr>
                        <td>${l.id}</td>
                        <td><span class="table-tag">${escapeHtml(l.tableName)}</span></td>
                        <td><span class="action-tag ${l.action.toLowerCase()}">${escapeHtml(l.action)}</span></td>
                        <td>${escapeHtml(l.details || '—')}</td>
                        <td>${escapeHtml(l.changedBy || 'system')}</td>
                        <td>${l.changedOn ? formatDate(l.changedOn) : '—'}</td>
                    </tr>
                `).join('');
            }

            renderPagination('auditLogsPagination', data.page, Math.ceil((data.total || 0) / PAGE_SIZE), (p) => {
                currentAuditPage = p;
                loadAuditLogs();
            });
        }
    } catch (error) {
        console.error('Failed to load audit logs:', error);
        tbody.innerHTML = '<tr><td colspan="6" class="loading-cell error">Failed to load audit logs</td></tr>';
    }
}

// ==========================================
// UTILITY FUNCTIONS
// ==========================================

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function formatDate(dateStr) {
    if (!dateStr) return '—';
    const d = new Date(dateStr);
    if (isNaN(d.getTime())) return dateStr;
    return d.toLocaleDateString('en-IN', {
        year: 'numeric', month: 'short', day: 'numeric',
        hour: '2-digit', minute: '2-digit'
    });
}

function getNPKClass(type, value) {
    if (!value && value !== 0) return '';
    switch (type) {
        case 'n': return value < 280 ? 'npk-low' : value <= 560 ? 'npk-medium' : 'npk-high';
        case 'p': return value < 10 ? 'npk-low' : value <= 25 ? 'npk-medium' : 'npk-high';
        case 'k': return value < 110 ? 'npk-low' : value <= 280 ? 'npk-medium' : 'npk-high';
        default: return '';
    }
}

function renderPagination(containerId, currentPage, totalPages, onPageClick) {
    const container = document.getElementById(containerId);
    if (!container || totalPages <= 1) {
        if (container) container.innerHTML = '';
        return;
    }

    let html = '';

    // Prev button
    html += `<button class="page-btn" ${currentPage <= 1 ? 'disabled' : ''} onclick="void(0)">‹ Prev</button>`;

    // Page numbers
    const maxVisible = 5;
    let start = Math.max(1, currentPage - Math.floor(maxVisible / 2));
    let end = Math.min(totalPages, start + maxVisible - 1);
    if (end - start < maxVisible - 1) start = Math.max(1, end - maxVisible + 1);

    if (start > 1) {
        html += `<button class="page-btn" onclick="void(0)">1</button>`;
        if (start > 2) html += `<span class="page-dots">...</span>`;
    }

    for (let i = start; i <= end; i++) {
        html += `<button class="page-btn ${i === currentPage ? 'active' : ''}" onclick="void(0)">${i}</button>`;
    }

    if (end < totalPages) {
        if (end < totalPages - 1) html += `<span class="page-dots">...</span>`;
        html += `<button class="page-btn" onclick="void(0)">${totalPages}</button>`;
    }

    // Next button
    html += `<button class="page-btn" ${currentPage >= totalPages ? 'disabled' : ''} onclick="void(0)">Next ›</button>`;

    container.innerHTML = html;

    // Attach click handlers
    container.querySelectorAll('.page-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const text = btn.textContent.trim();
            if (text === '‹ Prev' && currentPage > 1) {
                onPageClick(currentPage - 1);
            } else if (text === 'Next ›' && currentPage < totalPages) {
                onPageClick(currentPage + 1);
            } else {
                const num = parseInt(text);
                if (!isNaN(num) && num !== currentPage) {
                    onPageClick(num);
                }
            }
        });
    });
}
