package app.floatface.wear

import android.app.Application

/**
 * Application entry point (SPEC §13). Will own the AppContainer that wires the
 * ports (OnewheelTransport, RideRecorder, Clock, Ticker, etc.) and the
 * application-scoped OnewheelController. Implemented test-first by the swarm.
 */
class FloatfaceApplication : Application()
