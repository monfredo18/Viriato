// Gera docs/relatorio-viriato.pdf a partir de relatorio.html.
// Uso (precisa do pacote "playwright" com Chromium instalado):  node docs/relatorio/gerar-pdf.js
const { chromium } = require('playwright')
const path = require('path')

;(async () => {
  const browser = await chromium.launch()
  const page = await browser.newPage()
  await page.goto('file://' + path.resolve(__dirname, 'relatorio.html'))
  await page.waitForLoadState('networkidle')
  await page.pdf({
    path: path.resolve(__dirname, '..', 'relatorio-viriato.pdf'),
    format: 'A4',
    printBackground: true,
    preferCSSPageSize: false,
    displayHeaderFooter: true,
    headerTemplate: '<div></div>',
    footerTemplate:
      '<div style="font-size:8px;color:#667085;width:100%;padding:0 18mm;display:flex;justify-content:space-between;font-family:Arial,sans-serif">' +
      '<span>Viriato — Relatório do Sistema</span><span><span class="pageNumber"></span> / <span class="totalPages"></span></span></div>',
    margin: { top: '16mm', bottom: '18mm', left: '0', right: '0' },
  })
  await browser.close()
})()
