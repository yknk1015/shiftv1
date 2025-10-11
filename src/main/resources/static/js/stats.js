document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('stats-form');
    const status = document.getElementById('stats-status');
    const monthlySummary = document.getElementById('monthly-summary');
    const workloadTableBody = document.querySelector('#workload-table tbody');
    const distributionList = document.getElementById('distribution-list');

    function initFormDefaults() {
        const yearInput = form.querySelector('input[name="year"]');
        const monthInput = form.querySelector('input[name="month"]');
        const defaultYear = parseInt(form.dataset.defaultYear, 10);
        const defaultMonth = parseInt(form.dataset.defaultMonth, 10);

        if (!Number.isNaN(defaultYear)) {
            yearInput.value = defaultYear;
        }
        if (!Number.isNaN(defaultMonth)) {
            monthInput.value = defaultMonth;
        }
    }

    function setStatus(message, success = false, isError = false) {
        status.textContent = message;
        status.classList.remove('success', 'error');
        if (success) {
            status.classList.add('success');
        }
        if (isError) {
            status.classList.add('error');
        }
    }

    function renderMonthlySummary(summary) {
        if (!summary) {
            monthlySummary.innerHTML = '<p class="placeholder">統計情報がありません。</p>';
            return;
        }

        monthlySummary.innerHTML = `
            <div class="stat"><span>対象月</span><strong>${summary.month}</strong></div>
            <div class="stat"><span>総シフト数</span><strong>${summary.totalShifts} 件</strong></div>
            <div class="stat"><span>勤務した従業員</span><strong>${summary.uniqueEmployees} 名</strong></div>
        `;

        if (summary.shiftsByType) {
            Object.entries(summary.shiftsByType).forEach(([type, count]) => {
                monthlySummary.innerHTML += `<div class="stat"><span>${type}</span><strong>${count} 件</strong></div>`;
            });
        }
    }

    function renderWorkload(workload) {
        if (!workload || workload.length === 0) {
            workloadTableBody.innerHTML = '<tr><td colspan="2" class="placeholder">統計情報がありません。</td></tr>';
            return;
        }

        workloadTableBody.innerHTML = workload.map(item => `
            <tr>
                <td>${item.employeeName}</td>
                <td>${item.shiftCount}</td>
            </tr>
        `).join('');
    }

    function renderDistribution(distribution) {
        if (!distribution || !distribution.distribution || Object.keys(distribution.distribution).length === 0) {
            distributionList.innerHTML = '<li class="placeholder">統計情報がありません。</li>';
            return;
        }

        distributionList.innerHTML = Object.entries(distribution.distribution)
            .map(([shift, count]) => `<li><span>${shift}</span><strong>${count} 件</strong></li>`) 
            .join('');
    }

    async function fetchJson(url) {
        const params = new URLSearchParams(new FormData(form));
        const response = await fetch(`${url}?${params.toString()}`);
        if (!response.ok) {
            const text = await response.text();
            throw new Error(text || '統計情報の取得に失敗しました');
        }
        return await response.json();
    }

    async function loadStats(event) {
        if (event) {
            event.preventDefault();
        }
        setStatus('統計情報を読み込んでいます...', false);
        try {
            const [summary, workload, distribution] = await Promise.all([
                fetchJson('/api/schedule/stats/monthly'),
                fetchJson('/api/schedule/stats/employee-workload'),
                fetchJson('/api/schedule/stats/shift-distribution')
            ]);
            renderMonthlySummary(summary);
            renderWorkload(workload);
            renderDistribution(distribution);
            setStatus('統計情報を更新しました。', true);
        } catch (error) {
            renderMonthlySummary(null);
            renderWorkload(null);
            renderDistribution(null);
            setStatus(error.message, false, true);
        }
    }

    form.addEventListener('submit', loadStats);

    initFormDefaults();
    loadStats();
});
