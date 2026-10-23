// Touch paging leaves taps and remote focus navigation intact.
export function useSwipePages(previous,next) {
  let start=null
  return {
    touchStart(event){const t=event.touches?.[0];start=t?{x:t.clientX,y:t.clientY}:null},
    touchEnd(event){const t=event.changedTouches?.[0];if(!start||!t)return;const dx=t.clientX-start.x,dy=t.clientY-start.y;start=null;if(Math.abs(dx)<60||Math.abs(dx)<Math.abs(dy)*1.5)return;event.preventDefault();dx<0?next():previous()}
  }
}
