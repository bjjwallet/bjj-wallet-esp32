'use strict';

(() => {
  const translations = JSON.parse(document.getElementById('translations').textContent);
  const selector = document.getElementById('language');
  const status = document.getElementById('status');
  const address = document.querySelector('.address').textContent.trim();
  const requested = new URLSearchParams(location.search).get('lang');
  let language = Object.hasOwn(translations, requested) ? requested : 'pt';

  for (const [code, text] of Object.entries(translations)) {
    const option = document.createElement('option');
    option.value = code;
    option.textContent = text.name;
    selector.append(option);
  }

  function applyLanguage() {
    const text = translations[language];
    document.documentElement.lang = language === 'zh' ? 'zh-Hans' : language;
    document.title = `BJJ-Cold-Wallet · ${text.support}`;
    selector.value = language;
    document.querySelectorAll('[data-i18n]').forEach((element) => {
      element.textContent = text[element.dataset.i18n];
    });
    document.querySelectorAll('.qr').forEach((element) => {
      element.alt = `${text.qr} ${element.closest('.card').querySelector('.token').textContent.replace('₮', '').trim()} · ${element.closest('.card').querySelector('strong').textContent}`;
    });
    const readme = `../../README${language === 'pt' ? '' : `.${language}`}.md`;
    document.getElementById('home').href = readme;
    document.getElementById('back').href = readme;
    document.querySelectorAll('.address').forEach((element) => {
      element.setAttribute('aria-label', text.address);
    });
    status.textContent = '';
  }

  selector.addEventListener('change', () => {
    language = selector.value;
    applyLanguage();
  });

  // Local files and browsers without Clipboard API support use the selected-text fallback.
  function copyWithSelection(value) {
    const field = document.createElement('textarea');
    field.value = value;
    field.readOnly = true;
    field.style.position = 'fixed';
    field.style.opacity = '0';
    document.body.append(field);
    field.select();
    field.setSelectionRange(0, value.length);
    let copied = false;
    try {
      copied = document.execCommand('copy');
    } finally {
      field.remove();
    }
    return copied;
  }

  document.querySelectorAll('.copy').forEach((button) => {
    button.addEventListener('click', async () => {
      button.disabled = true;
      let copied = false;
      try {
        if (navigator.clipboard && window.isSecureContext) {
          await navigator.clipboard.writeText(address);
          copied = true;
        }
      } catch {
        // Clipboard access may be denied; try the local-file-compatible path next.
      }
      try {
        if (!copied) copied = copyWithSelection(address);
        status.textContent = translations[language][copied ? 'copied' : 'failed'];
        if (!copied) {
          const target = button.closest('.card').querySelector('.address');
          target.focus();
          const range = document.createRange();
          range.selectNodeContents(target);
          const selection = window.getSelection();
          selection.removeAllRanges();
          selection.addRange(range);
        }
      } catch {
        status.textContent = translations[language].failed;
      } finally {
        button.disabled = false;
        if (copied) button.focus();
      }
    });
  });

  applyLanguage();
})();
