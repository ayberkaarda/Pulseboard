import { render } from '@testing-library/angular';

import { SeverityGlyphComponent } from './severity-glyph.component';
import { Severity } from './signal.model';

describe('SeverityGlyphComponent', () => {
  it.each<[Severity, number]>([
    ['LOW', 1],
    ['MEDIUM', 2],
    ['HIGH', 3],
  ])('fills %s up to %i of three bars', async (severity, filled) => {
    const { container } = await render(SeverityGlyphComponent, { inputs: { severity } });

    expect(container.querySelectorAll('rect')).toHaveLength(3);
    expect(container.querySelectorAll('rect.on')).toHaveLength(filled);
    expect(container).toHaveAttribute('data-severity', severity);
  });
});
