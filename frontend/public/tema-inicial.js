// Aplica o tema salvo (ou a preferência do sistema) antes da primeira pintura.
// Fica em arquivo próprio (e não dentro do HTML) para a política de segurança do nginx bloquear scripts embutidos.
(function () {
  try {
    var t = localStorage.getItem('viriato.tema');
    if (t !== 'claro' && t !== 'escuro') t = matchMedia('(prefers-color-scheme: dark)').matches ? 'escuro' : 'claro';
    document.documentElement.dataset.tema = t;
  } catch (e) {}
})();
