import { useCallback, useEffect, useState } from 'react'
import { api } from './api.js'

/** Loads a GET resource with loading/error state; `reload` refetches (e.g. after an action). */
export function useResource(path, params) {
  const key = path ? `${path}?${JSON.stringify(params ?? {})}` : null
  const [state, setState] = useState({ data: undefined, error: null, loading: Boolean(key) })

  const load = useCallback(
    (signal) => {
      if (!key) return
      setState((previous) => ({ ...previous, loading: true, error: null }))
      api.get(path, params).then(
        (data) => !signal?.aborted && setState({ data, error: null, loading: false }),
        (error) => !signal?.aborted && setState((previous) => ({ ...previous, error, loading: false })),
      )
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps -- key captures path and params
    [key],
  )

  useEffect(() => {
    const controller = new AbortController()
    load(controller.signal)
    return () => controller.abort()
  }, [load])

  return { ...state, reload: load, setData: (data) => setState((s) => ({ ...s, data })) }
}
