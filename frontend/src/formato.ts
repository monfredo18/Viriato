/** Formatações de data e número compartilhadas pelas telas. */
export const fmtData = (d: string | null | undefined) => (d ? new Date(d + 'T00:00:00').toLocaleDateString('pt-BR') : '—')
export const dec = (v: number) => v.toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
/** Hoje como aaaa-mm-dd, no horário local (valor de input type="date"). */
export const hojeIso = () => new Date().toLocaleDateString('sv-SE')
