document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('schedule-form');
    const status = document.getElementById('schedule-status');
    const tableBody = document.querySelector('#schedule-table tbody');
    const loadButton = document.getElementById('load-schedule');

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

    function renderPlaceholder(message) {
        tableBody.innerHTML = `<tr><td colspan="5" class="placeholder">${message}</td></tr>`;
    }

    function renderSchedule(assignments) {
        if (!assignments || assignments.length === 0) {
            renderPlaceholder('対象月のシフトが見つかりませんでした。');
            return;
        }

        tableBody.innerHTML = assignments.map(item => `
            <tr>
                <td>${item.workDate}</td>
                <td>${item.shiftName}</td>
                <td>${item.startTime}</td>
                <td>${item.endTime}</td>
                <td>${item.employeeName}</td>
            </tr>
        `).join('');
    }

    async function requestSchedule(url) {
        const params = new URLSearchParams(new FormData(form));
        const requestUrl = `${url}?${params.toString()}`;
        const response = await fetch(requestUrl, { method: url.includes('generate') ? 'POST' : 'GET' });
        if (!response.ok) {
            const text = await response.text();
            throw new Error(text || 'シフトの取得に失敗しました');
        }
        return await response.json();
    }

    async function generateSchedule(event) {
        event.preventDefault();
        setStatus('シフトを生成しています...', false);
        try {
            const assignments = await requestSchedule('/api/schedule/generate');
            renderSchedule(assignments);
            setStatus('シフトを生成しました。', true);
        } catch (error) {
            renderPlaceholder('シフト生成に失敗しました。');
            setStatus(error.message, false, true);
        }
    }

    async function loadSchedule() {
        setStatus('既存のシフトを読み込んでいます...', false);
        try {
            const assignments = await requestSchedule('/api/schedule');
            renderSchedule(assignments);
            setStatus('既存シフトを読み込みました。', true);
        } catch (error) {
            renderPlaceholder('シフトの読み込みに失敗しました。');
            setStatus(error.message, false, true);
        }
    }

    form.addEventListener('submit', generateSchedule);
    loadButton.addEventListener('click', loadSchedule);

    initFormDefaults();
    loadSchedule();
});
