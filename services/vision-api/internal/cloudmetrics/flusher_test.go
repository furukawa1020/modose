package cloudmetrics

import (
	"context"
	"errors"
	"testing"
	"time"
)

type flushSinkStub struct {
	calls    int
	failure  error
	cancel   context.CancelFunc
	deadline bool
}

func (sink *flushSinkStub) Flush(ctx context.Context) error {
	sink.calls++
	_, sink.deadline = ctx.Deadline()
	if sink.calls == 2 && sink.cancel != nil {
		sink.cancel()
	}
	if sink.calls == 1 {
		return sink.failure
	}
	return nil
}

func TestRunFlushLoopReportsFailureAndContinues(t *testing.T) {
	t.Parallel()

	ctx, cancel := context.WithCancel(context.Background())
	failure := errors.New("monitoring unavailable")
	sink := &flushSinkStub{
		failure: failure,
		cancel:  cancel,
	}
	ticks := make(chan time.Time, 2)
	ticks <- time.Now()
	ticks <- time.Now()
	var reported []error

	err := runFlushLoop(
		ctx,
		sink,
		ticks,
		time.Second,
		func(err error) {
			reported = append(reported, err)
		},
	)

	if err != nil {
		t.Fatalf("runFlushLoop() error = %v", err)
	}
	if sink.calls != 2 {
		t.Fatalf("calls = %d", sink.calls)
	}
	if !sink.deadline {
		t.Fatal("flush context has no deadline")
	}
	if len(reported) != 1 || !errors.Is(reported[0], failure) {
		t.Fatalf("reported = %#v", reported)
	}
}

func TestRunPeriodicRejectsInvalidConfiguration(t *testing.T) {
	t.Parallel()

	sink := &flushSinkStub{}
	tests := []struct {
		name     string
		ctx      context.Context
		sink     FlushSink
		interval time.Duration
		deadline time.Duration
	}{
		{
			name: "context required", sink: sink,
			interval: time.Second, deadline: time.Second,
		},
		{
			name: "sink required", ctx: context.Background(),
			interval: time.Second, deadline: time.Second,
		},
		{
			name: "positive interval", ctx: context.Background(), sink: sink,
			deadline: time.Second,
		},
		{
			name: "deadline no longer than interval",
			ctx: context.Background(), sink: sink,
			interval: time.Second, deadline: 2 * time.Second,
		},
	}

	for _, test := range tests {
		test := test
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			if err := RunPeriodic(
				test.ctx,
				test.sink,
				test.interval,
				test.deadline,
				nil,
			); err == nil {
				t.Fatal("RunPeriodic() error = nil")
			}
		})
	}
}
