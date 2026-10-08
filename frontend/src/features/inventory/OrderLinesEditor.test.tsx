import { describe, expect, it } from 'vitest'
import { ApiError } from '@/lib/api/client'
import { newLine, serverLineErrors, validateLines } from './OrderLinesEditor'

const widget = { id: 'pr-widget', sku: 'W-1', name: 'Widget', unit: 'each' }

describe('order lines', () => {
  it('needs a line, a product once, a positive quantity and a valid price', () => {
    expect(validateLines([], 'unitCost', true)).toEqual({ lines: 'Add at least one line.' })
    expect(
      validateLines(
        [newLine(widget, '2', '1.5'), newLine(widget, '0', ''), newLine(null, '1', '-1')],
        'unitCost',
        true,
      ),
    ).toEqual({
      'lines[1].productId': 'This product is already on the order.',
      'lines[1].quantity': 'Enter a quantity greater than 0.',
      'lines[1].unitCost': 'Enter an amount.',
      'lines[2].productId': 'Choose a product.',
      'lines[2].unitCost': 'Enter an amount like 12.50.',
    })
  })

  it('lets a sales price stay blank', () => {
    expect(validateLines([newLine(widget, '1', '')], 'unitPrice', false)).toEqual({})
  })

  it('keeps the server line errors', () => {
    const error = new ApiError({
      status: 400,
      detail: 'Request validation failed.',
      errors: [
        { field: 'lines[0].quantity', message: 'Use at most 4 decimal places.' },
        { field: 'supplierId', message: 'Choose a supplier.' },
      ],
    })
    expect(serverLineErrors(error)).toEqual({
      'lines[0].quantity': 'Use at most 4 decimal places.',
    })
    expect(serverLineErrors(new Error('x'))).toBeNull()
  })
})
