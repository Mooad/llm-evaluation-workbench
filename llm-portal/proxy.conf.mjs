// Le portail appelle /api/... et /rag-api/... ; le serveur de dev relaie vers le Gateway et vers llm-rag.
// Meme origine pour le navigateur : aucun CORS a ouvrir dans les services.
// GATEWAY_URL et RAG_URL permettent de viser d'autres instances (ex. http://localhost:8091).
// Pas "/rag" : c'est la route de l'ecran RAG Chat, le proxy l'intercepterait (404 de llm-rag).
export default {
  '/api': {
    target: process.env.GATEWAY_URL ?? 'http://localhost:8081',
    pathRewrite: { '^/api': '' },
    changeOrigin: true,
  },
  '/rag-api': {
    target: process.env.RAG_URL ?? 'http://localhost:8082',
    pathRewrite: { '^/rag-api': '' },
    changeOrigin: true,
  },
};
