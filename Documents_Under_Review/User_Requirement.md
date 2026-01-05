1. User wants to exit only if stock price is expected to go down.
2. We've seen cases where placed orders are executed before 44-45 minutes, so avoid such executions if prices are not expected to go down (do not place or keep orders when downside is not expected).
3. Persist market depth only if there is any change in either bid or asks.
4. Allow early exits when upper circuit is expected to break (LTP at UC but equilibrium slips below UC with negative imbalance), in any session.
5. Redefine downside gate using session trend (TrendDelta or SessionDrift), not `P_prev_close`.
6. `P_map = Lower Circuit (LC)` and `margin = 0`.
7. If `upper_circuit` is missing, derive it using `Open_0930` and `P_prev_close` (when `Open_0930 > P_prev_close`, set `UC = Open_0930`).
8. If `upper_circuit` is missing and `Open_0930 <= P_prev_close`, treat UC as unknown and skip UC-based triggers for that session.
