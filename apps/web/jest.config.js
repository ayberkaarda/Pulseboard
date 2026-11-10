/** @type {import('jest').Config} */
module.exports = {
  testEnvironment: 'jsdom',
  roots: ['<rootDir>/src'],
  testMatch: ['**/*.spec.ts'],
  setupFilesAfterEnv: ['<rootDir>/src/test-setup.ts'],
  collectCoverageFrom: ['src/**/*.ts', '!src/main.ts', '!src/test-setup.ts', '!src/**/*.spec.ts'],
  coverageDirectory: '<rootDir>/coverage',
  clearMocks: true,
};
