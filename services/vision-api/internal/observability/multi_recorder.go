package observability

import "errors"

// MultiRecorder sends each event to every configured recorder.
type MultiRecorder struct {
	recorders []Recorder
}

// NewMultiRecorder creates a recorder that fans out events in registration order.
func NewMultiRecorder(recorders ...Recorder) *MultiRecorder {
	configured := make([]Recorder, 0, len(recorders))
	for _, recorder := range recorders {
		if recorder != nil {
			configured = append(configured, recorder)
		}
	}
	return &MultiRecorder{recorders: configured}
}

// Record attempts every recorder and joins any failures.
func (recorder *MultiRecorder) Record(event Event) error {
	var failures []error
	for _, target := range recorder.recorders {
		if err := target.Record(event); err != nil {
			failures = append(failures, err)
		}
	}
	return errors.Join(failures...)
}
