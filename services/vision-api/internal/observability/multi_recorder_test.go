package observability

import (
	"errors"
	"reflect"
	"testing"
)

type orderedRecorderStub struct {
	id    string
	calls *[]string
	err   error
}

func (recorder *orderedRecorderStub) Record(Event) error {
	*recorder.calls = append(*recorder.calls, recorder.id)
	return recorder.err
}

func TestMultiRecorderCallsEveryRecorderInOrder(t *testing.T) {
	t.Parallel()

	firstFailure := errors.New("first recorder failed")
	lastFailure := errors.New("last recorder failed")
	calls := []string{}
	recorder := NewMultiRecorder(
		&orderedRecorderStub{
			id:    "json",
			calls: &calls,
			err:   firstFailure,
		},
		&orderedRecorderStub{
			id:    "metrics",
			calls: &calls,
		},
		&orderedRecorderStub{
			id:    "audit",
			calls: &calls,
			err:   lastFailure,
		},
	)

	err := recorder.Record(Event{})

	if !reflect.DeepEqual(calls, []string{"json", "metrics", "audit"}) {
		t.Fatalf("calls = %#v", calls)
	}
	if !errors.Is(err, firstFailure) || !errors.Is(err, lastFailure) {
		t.Fatalf("joined error = %v", err)
	}
}

func TestMultiRecorderIgnoresNilAndAllowsEmptyConfiguration(t *testing.T) {
	t.Parallel()

	calls := []string{}
	recorder := NewMultiRecorder(
		nil,
		&orderedRecorderStub{id: "configured", calls: &calls},
		nil,
	)

	if err := recorder.Record(Event{}); err != nil {
		t.Fatalf("Record() error = %v", err)
	}
	if !reflect.DeepEqual(calls, []string{"configured"}) {
		t.Fatalf("calls = %#v", calls)
	}
	if err := NewMultiRecorder().Record(Event{}); err != nil {
		t.Fatalf("empty Record() error = %v", err)
	}
}
