// See touch_observer.h.
#import "touch_observer.h"
#import <UIKit/UIGestureRecognizerSubclass.h>

@implementation PBTouchObserver

- (instancetype)init {
    return [self initWithTarget:nil action:nil];
}

- (instancetype)initWithTarget:(id)target action:(SEL)action {
    self = [super initWithTarget:target action:action];
    if (self) {
        self.cancelsTouchesInView = NO;
        self.delaysTouchesBegan = NO;
        self.delaysTouchesEnded = NO;
    }
    return self;
}

- (void)touchesMoved:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [super touchesMoved:touches withEvent:event];
    void (^moved)(NSSet<UITouch *> *, UIEvent *) = self.onTouchesMoved;
    if (moved) moved(touches, event);
}

- (void)touchesEnded:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [super touchesEnded:touches withEvent:event];
    [self failWhenDone:event];
}

- (void)touchesCancelled:(NSSet<UITouch *> *)touches withEvent:(UIEvent *)event {
    [super touchesCancelled:touches withEvent:event];
    [self failWhenDone:event];
}

// With no touch of the sequence left, failed: ready for the next one
- (void)failWhenDone:(UIEvent *)event {
    for (UITouch *touch in [event touchesForGestureRecognizer:self]) {
        if (touch.phase != UITouchPhaseEnded && touch.phase != UITouchPhaseCancelled) return;
    }
    self.state = UIGestureRecognizerStateFailed;
}

- (BOOL)canPreventGestureRecognizer:(UIGestureRecognizer *)preventedGestureRecognizer {
    return NO;
}

- (BOOL)canBePreventedByGestureRecognizer:(UIGestureRecognizer *)preventingGestureRecognizer {
    return NO;
}

@end
