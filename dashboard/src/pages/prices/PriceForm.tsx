import { useState, type FormEvent } from 'react'
import type { UpsertPriceInput } from '../../api/types'
import { Field } from '../../components/Field'
import { validatePriceForm, type PriceFormErrors, type PriceFormValues } from './priceModel'

interface PriceFormProps {
  values: PriceFormValues
  providers: string[]
  busy: boolean
  onChange: (values: PriceFormValues) => void
  onSubmit: (input: UpsertPriceInput) => Promise<void>
  onReset: () => void
}

export function PriceForm({ values, providers, busy, onChange, onSubmit, onReset }: PriceFormProps) {
  const [errors, setErrors] = useState<PriceFormErrors>({})

  const set = (field: keyof PriceFormValues) => (event: { target: { value: string } }) => {
    onChange({ ...values, [field]: event.target.value })
    if (errors[field]) {
      setErrors((current) => {
        const next = { ...current }
        delete next[field]
        return next
      })
    }
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const result = validatePriceForm(values)
    setErrors(result.errors)
    if (result.input) await onSubmit(result.input)
  }

  const providerListId = 'price-provider-options'

  return (
    <form onSubmit={submit} className="stack" aria-labelledby="price-form-heading">
      <div className="price-form-grid">
        <Field label="공급처" error={errors.provider} hint="설정의 공급처 이름과 같아야 합니다">
          {(id, invalid) => (
            <>
              <input id={id} className="input" list={providerListId} value={values.provider} onChange={set('provider')} aria-invalid={invalid} />
              <datalist id={providerListId}>
                {providers.map((name) => (
                  <option key={name} value={name} />
                ))}
              </datalist>
            </>
          )}
        </Field>
        <Field label="모델" error={errors.model} className="field-wide">
          {(id, invalid) => <input id={id} className="input" value={values.model} onChange={set('model')} aria-invalid={invalid} />}
        </Field>
        <Field label="입력 단가" error={errors.inputPerMtok}>
          {(id, invalid) => <input id={id} className="input num" inputMode="decimal" value={values.inputPerMtok} onChange={set('inputPerMtok')} aria-invalid={invalid} />}
        </Field>
        <Field label="출력 단가" error={errors.outputPerMtok}>
          {(id, invalid) => <input id={id} className="input num" inputMode="decimal" value={values.outputPerMtok} onChange={set('outputPerMtok')} aria-invalid={invalid} />}
        </Field>
        <Field label="캐시 입력 단가 (선택)" error={errors.cachedInputPerMtok} hint="비우면 입력 단가로 계산">
          {(id, invalid) => (
            <input id={id} className="input num" inputMode="decimal" value={values.cachedInputPerMtok} onChange={set('cachedInputPerMtok')} aria-invalid={invalid} />
          )}
        </Field>
        <Field label="적용 시작 (UTC)" error={errors.effectiveFrom}>
          {(id, invalid) => <input id={id} className="input" type="date" value={values.effectiveFrom} onChange={set('effectiveFrom')} aria-invalid={invalid} />}
        </Field>
      </div>
      <div className="row">
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? '저장 중…' : '저장'}
        </button>
        <button type="button" className="btn" onClick={onReset} disabled={busy}>
          비우기
        </button>
        <span className="muted" style={{ fontSize: 13 }}>
          같은 공급처·모델·적용 시작으로 저장하면 그 행의 단가가 바뀝니다. 가격이 바뀐 날짜부터 적용하려면 적용 시작을 그 날짜로 바꿔 새 행으로 추가하세요.
        </span>
      </div>
    </form>
  )
}
