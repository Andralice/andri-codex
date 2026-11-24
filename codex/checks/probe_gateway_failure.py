"""Print upstream gateway errors while running the protocol fixture manually."""

from check_thread_gateway import Session, main

original = Session.complete


def inspect(self, future, request_id, backend, method):
    response = future.result()
    if "error" in response:
        print("fixture_error", method, response["error"], flush=True)
    return original(self, future, request_id, backend, method)


Session.complete = inspect
main()
