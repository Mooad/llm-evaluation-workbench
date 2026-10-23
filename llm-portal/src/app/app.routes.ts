import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    title: 'Playground — LLM Portal',
    loadComponent: () => import('./playground/playground').then((m) => m.Playground),
  },
  {
    path: 'rag',
    title: 'RAG Chat — LLM Portal',
    loadComponent: () => import('./rag-chat/rag-chat').then((m) => m.RagChat),
  },
  {
    path: 'rag-eval',
    title: 'Évaluation RAG — LLM Portal',
    loadComponent: () => import('./rag-eval/rag-eval').then((m) => m.RagEval),
  },
  {
    path: 'evaluations',
    title: 'Évaluations — LLM Portal',
    loadComponent: () => import('./evaluations/evaluations').then((m) => m.Evaluations),
  },
  {
    path: 'configuration',
    title: 'Configuration — LLM Portal',
    loadComponent: () => import('./configuration/configuration').then((m) => m.Configuration),
  },
  { path: '**', redirectTo: '' },
];
