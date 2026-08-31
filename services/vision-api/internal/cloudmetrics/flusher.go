package cloudmetrics

import (
	"context"
	"fmt"
	"time"
)

type FlushSink interface {
	Flush(context.Context) error
}

type FlushError struct {
	Reason string
}

func (e *FlushError) Error() string {
	return fmt.Sprintf("periodic metric flush rejected: %s", e.Reason)
}

func RunPeriodic(
	ctx context.Context,
	sink FlushSink,
	interval time.Duration,
	deadline time.Duration,
	report func(error),
) error {
	if ctx == nil {
		return &FlushError{Reason: "context_required"}
	}
	if sink == nil {
		return &FlushError{Reason: "sink_required"}
	}
	if interval <= 0 {
		return &FlushError{Reason: "interval_out_of_range"}
	}
	if deadline <= 0 || deadline > interval {
		return &FlushError{Reason: "deadline_out_of_range"}
	}

	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	return runFlushLoop(ctx, sink, ticker.C, deadline, report)
}

func runFlushLoop(
	ctx context.Context,
	sink FlushSink,
	ticks <-chan time.Time,
	deadline time.Duration,
	report func(error),
) error {
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticks:
			flushContext, cancel := context.WithTimeout(ctx, deadline)
			err := sink.Flush(flushContext)
			cancel()
			if err != nil && report != nil {
				report(err)
			}
		}
	}
}
