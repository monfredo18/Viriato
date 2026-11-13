import type { ReactNode } from 'react'

/** Ícones de traço (24x24), todos no mesmo estilo; a cor vem do texto ao redor (currentColor). */
const base = { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true } as const

const desenhos = {
  inicio: <><path d="M3 11.5 12 4l9 7.5" /><path d="M5.5 10v9.5h4.5V14h4v5.5h4.5V10" /></>,
  processos: <><path d="M8 6h12M8 12h12M8 18h12" /><circle cx="4" cy="6" r="1" /><circle cx="4" cy="12" r="1" /><circle cx="4" cy="18" r="1" /></>,
  tarefas: <><rect x="4" y="4" width="16" height="16" rx="3" /><path d="m8.5 12.3 2.5 2.5 4.7-5.2" /></>,
  painel: <><path d="M4 20V10M10 20V4M16 20v-7M22 20H2" /></>,
  cadastros: <><ellipse cx="12" cy="6" rx="7" ry="3" /><path d="M5 6v6c0 1.7 3.1 3 7 3s7-1.3 7-3V6" /><path d="M5 12v6c0 1.7 3.1 3 7 3s7-1.3 7-3v-6" /></>,
  fluxos: <><circle cx="6" cy="6" r="2.2" /><circle cx="18" cy="12" r="2.2" /><circle cx="6" cy="18" r="2.2" /><path d="M8.2 6H12a3 3 0 0 1 3 3v.8M8.2 18H12a3 3 0 0 0 3-3v-.8" /></>,
  usuarios: <><circle cx="9" cy="8" r="3.2" /><path d="M3 20c0-3.3 2.7-5.5 6-5.5s6 2.2 6 5.5" /><path d="M16 5.2a3.1 3.1 0 0 1 0 5.6M18 14.8c1.8.7 3 2.4 3 5.2" /></>,
  relatorios: <><path d="M7 3h7l5 5v13H7z" /><path d="M14 3v5h5M10 13h6M10 17h6" /></>,
  busca: <><circle cx="11" cy="11" r="6.5" /><path d="m20 20-4.2-4.2" /></>,
  menu: <><path d="M4 7h16M4 12h16M4 17h16" /></>,
  mais: <><path d="M12 5v14M5 12h14" /></>,
  relogio: <><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></>,
  alerta: <><path d="M12 3.5 2.8 19.5h18.4z" /><path d="M12 10v4.5M12 17.2v.1" /></>,
  check: <><circle cx="12" cy="12" r="8.5" /><path d="m8.3 12.3 2.5 2.5 4.9-5.4" /></>,
  ampulheta: <><path d="M7 3h10M7 21h10M8 3c0 5 3 6 4 9-1 3-4 4-4 9M16 3c0 5-3 6-4 9 1 3 4 4 4 9" /></>,
  calendario: <><rect x="4" y="5" width="16" height="15" rx="2.5" /><path d="M8 3v4M16 3v4M4 10h16" /></>,
  ajuda: <><circle cx="12" cy="12" r="9" /><path d="M9.6 9.4a2.5 2.5 0 0 1 4.8.9c0 1.7-2.4 2.1-2.4 3.6M12 17.2v.1" /></>,
  enviar: <><path d="M21 3 10.5 13.5M21 3l-6.8 18-3.7-7.5L3 9.8z" /></>,
  fechar: <><path d="M6 6l12 12M18 6 6 18" /></>,
  voltar: <><path d="M15 5l-7 7 7 7" /></>,
  sair: <><path d="M10 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4M15 8l4 4-4 4M19 12H9" /></>,
} satisfies Record<string, ReactNode>

export type NomeIcone = keyof typeof desenhos

export default function Icone({ nome, tamanho = 20 }: { nome: NomeIcone; tamanho?: number }) {
  return <svg {...base} width={tamanho} height={tamanho}>{desenhos[nome]}</svg>
}
