// A gesture recognizer that sees the touches of its view without taking any, for the predicted and
// coalesced touches of the ink on iOS (docs/plans/ink-rendering-camera.md): it never recognizes (it
// fails once the touches of a sequence are all gone), cancels nothing, delays nothing and prevents no
// other recognizer, so the gestures of Compose get every touch as before. Written in Objective-C
// (touch_observer.m) because Kotlin/Native cannot override the methods UIGestureRecognizer leaves to
// its subclasses (a category); read by pageboard-canvas through cinterop.
#ifndef PAGEBOARD_TOUCH_OBSERVER_H
#define PAGEBOARD_TOUCH_OBSERVER_H

#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

@interface PBTouchObserver : UIGestureRecognizer

/** Called with the touches moved and their event, after the recognizer has taken them in. */
@property (nonatomic, copy, nullable) void (^onTouchesMoved)(NSSet<UITouch *> *touches, UIEvent *event);

/** An observer with no target: it calls its block only. */
- (instancetype)init;

@end

NS_ASSUME_NONNULL_END

#endif
