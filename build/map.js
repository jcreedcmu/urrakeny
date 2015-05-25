var _ = require('underscore');
var u = require('./util');

u.with_image(__dirname + '/../resources/public/map.png', function(imdat, d) {
  var newdat = d.createImageData(imdat.width, imdat.height);
  for (var y = 0; y < imdat.height; y++) {
    for (var x = 0; x < imdat.width; x++) {
      var here = u.get(imdat, x, y);
      var others = [u.get(imdat, x-1, y), u.get(imdat, x, y-1),
		    u.get(imdat, x, y+1), u.get(imdat, x+1, y)]

      u.set3(newdat, x, y, (here > others[0] || here > others[1] || here > others[2] || here > others[3])  ?
	     [0,0,0] :
	     (u.get(imdat, x, y) == 0x4b ?
	      [128, 192, 192] :
	      [255, 255, 255])  );
    }
  }


  u.output_image(__dirname + '/map-out.png', d, newdat);
});

function optimizePts(pts) {
  var newpts = [];
  for (var i = 0; i < pts.length; i++) {
   if (i % 3 == 0 || i == pts.length - 1)
     newpts.push(pts[i]);
  }
  return newpts;
}
