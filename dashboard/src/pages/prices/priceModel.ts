import type { Price, UpsertPriceInput } from '../../api/types'
import { ISO_DATE } from '../../usage/period'

export interface PriceFormValues {
  provider: string
  model: string
  inputPerMtok: string
  outputPerMtok: string
  cachedInputPerMtok: string
  effectiveFrom: string
}

export type PriceFormErrors = Partial<Record<keyof PriceFormValues, string>>

const RATE = /^\d+(\.\d{1,8})?$/

export const REQUIRED_MESSAGE = '필수 입력입니다.'
export const RATE_MESSAGE = '0 이상의 숫자를 소수 8자리까지 입력하세요.'
export const DATE_MESSAGE = 'YYYY-MM-DD 형식으로 입력하세요.'

/** Client-side mirror of the server's PriceSpec rules, so that mistakes are caught before the request. */
export function validatePriceForm(values: PriceFormValues): { errors: PriceFormErrors; input: UpsertPriceInput | null } {
  const errors: PriceFormErrors = {}
  const provider = values.provider.trim()
  const model = values.model.trim()
  if (!provider) errors.provider = REQUIRED_MESSAGE
  if (!model) errors.model = REQUIRED_MESSAGE
  if (!values.inputPerMtok.trim()) errors.inputPerMtok = REQUIRED_MESSAGE
  else if (!RATE.test(values.inputPerMtok.trim())) errors.inputPerMtok = RATE_MESSAGE
  if (!values.outputPerMtok.trim()) errors.outputPerMtok = REQUIRED_MESSAGE
  else if (!RATE.test(values.outputPerMtok.trim())) errors.outputPerMtok = RATE_MESSAGE
  if (values.cachedInputPerMtok.trim() && !RATE.test(values.cachedInputPerMtok.trim())) errors.cachedInputPerMtok = RATE_MESSAGE
  if (!values.effectiveFrom.trim()) errors.effectiveFrom = REQUIRED_MESSAGE
  else if (!ISO_DATE.test(values.effectiveFrom.trim())) errors.effectiveFrom = DATE_MESSAGE
  if (Object.keys(errors).length > 0) return { errors, input: null }
  return {
    errors,
    input: {
      provider,
      model,
      inputPerMtok: Number(values.inputPerMtok.trim()),
      outputPerMtok: Number(values.outputPerMtok.trim()),
      cachedInputPerMtok: values.cachedInputPerMtok.trim() ? Number(values.cachedInputPerMtok.trim()) : null,
      effectiveFrom: values.effectiveFrom.trim(),
    },
  }
}

export function emptyPriceForm(effectiveFrom: string): PriceFormValues {
  return { provider: '', model: '', inputPerMtok: '', outputPerMtok: '', cachedInputPerMtok: '', effectiveFrom }
}

/** Pre-fills the form from a row so that saving replaces that row's rates (same provider, model and effective date). */
export function priceToForm(price: Price): PriceFormValues {
  return {
    provider: price.provider,
    model: price.model,
    inputPerMtok: String(price.inputPerMtok),
    outputPerMtok: String(price.outputPerMtok),
    cachedInputPerMtok: price.cachedInputPerMtok === null ? '' : String(price.cachedInputPerMtok),
    effectiveFrom: price.effectiveFrom.slice(0, 10),
  }
}

export interface PriceGroup {
  provider: string
  model: string
  latest: Price
  history: Price[]
}

export function groupKey(group: PriceGroup): string {
  return `${group.provider}/${group.model}`
}

/** One group per (provider, model): the row in force latest first, older rows as history. */
export function groupPrices(prices: Price[]): PriceGroup[] {
  const groups = new Map<string, Price[]>()
  for (const price of prices) {
    const key = `${price.provider}\u0000${price.model}`
    const list = groups.get(key)
    if (list) list.push(price)
    else groups.set(key, [price])
  }
  return [...groups.values()]
    .map((rows) => {
      const sorted = [...rows].sort((a, b) => b.effectiveFrom.localeCompare(a.effectiveFrom))
      return { provider: sorted[0].provider, model: sorted[0].model, latest: sorted[0], history: sorted.slice(1) }
    })
    .sort((a, b) => a.provider.localeCompare(b.provider) || a.model.localeCompare(b.model))
}
