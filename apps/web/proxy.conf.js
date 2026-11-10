// Dev-server proxy for `ng serve`.
// Local default targets api-core on the host; Docker Compose sets API_PROXY_TARGET=http://api-core:8080.
const target = process.env['API_PROXY_TARGET'] || 'http://localhost:8080';

module.exports = {
  '/api': {
    target,
    secure: false,
    changeOrigin: true,
  },
};
