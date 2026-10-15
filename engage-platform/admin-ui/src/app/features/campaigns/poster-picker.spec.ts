import { posterProblem } from './poster-picker';

describe('poster picker', () => {
  it('accepts JPEG and PNG up to 1 MB', () => {
    expect(posterProblem({ type: 'image/png', size: 400_000 })).toBeNull();
    expect(posterProblem({ type: 'image/jpeg', size: 1_048_576 })).toBeNull();
  });

  it('refuses other types and big files with a plain reason', () => {
    expect(posterProblem({ type: 'image/gif', size: 1000 })).toContain('JPEG or PNG');
    expect(posterProblem({ type: 'image/png', size: 2_000_000 })).toContain('1953 KB');
  });
});
